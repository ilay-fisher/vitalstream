"""The copilot agent: a LangGraph tool-calling loop (`llm` ⇄ `tools`) streaming custom events.

We use plain LangGraph rather than the `deepagents` package: the agent needs no planning
scratchpad, sub-agents or virtual file system, only a bounded model/tool loop, so the extra
abstraction would add weight without behaviour.
"""

import asyncio
import json
import operator
import time
from collections.abc import AsyncIterator
from dataclasses import dataclass, field
from typing import Annotated, Any, TypedDict

import structlog
from langgraph.config import get_stream_writer
from langgraph.graph import END, START, StateGraph

from copilot.llm.types import LLMProvider, Message, TextDelta, ToolCall, ToolUse, Usage
from copilot.metrics import LLM_LATENCY, LLM_TOKENS
from copilot.tools import CopilotTools, ToolError

log = structlog.get_logger()

SYSTEM_PROMPT = (
    "You are the VitalStream clinical copilot, a decision-support assistant for ward clinicians. "
    "You never diagnose or prescribe; you summarise monitored data and point to guidelines.\n"
    "Rules:\n"
    "1. Only state vital-sign values that came from the get_patient_vitals tool. Never invent, "
    "estimate or extrapolate vitals. If data is missing or a tool fails, say so.\n"
    "2. Use compute_news2 for scores; do not calculate them yourself.\n"
    "3. Support clinical statements with search_guidelines and cite sources as [n] using the "
    "'ref' numbers returned by the tool.\n"
    "4. Be concise, flag urgent findings first, and remind the reader that the final decision "
    "rests with the treating clinician."
)
STEP_LIMIT_TEXT = (
    "I stopped at the step limit before finishing. Please ask a narrower question."
)


@dataclass(frozen=True, slots=True)
class AgentEvent:
    """One server-sent event produced while the agent runs."""

    type: str
    data: dict[str, Any]


class AgentState(TypedDict):
    """LangGraph state: the running transcript and the number of model calls so far."""

    messages: Annotated[list[Message], operator.add]
    steps: Annotated[int, operator.add]


@dataclass(slots=True)
class _RunContext:
    """Per-run bookkeeping (citation numbering must be stable within one answer)."""

    citations: dict[str, int] = field(default_factory=dict)


class CopilotAgent:
    """Runs one user turn through the LangGraph loop and yields :class:`AgentEvent`s."""

    def __init__(self, llm: LLMProvider, tools: CopilotTools, max_steps: int = 6) -> None:
        self._llm = llm
        self._tools = tools
        self._max_steps = max_steps

    async def run(
        self, history: list[Message], user_message: str, patient_id: str | None
    ) -> AsyncIterator[AgentEvent]:
        """Stream `token`, `tool_call`, `citation` events and a final `done` event."""
        system = SYSTEM_PROMPT + (f"\nPatient context: {patient_id}" if patient_id else "")
        initial: AgentState = {
            "messages": [
                Message("system", system),
                *history,
                Message("user", user_message),
            ],
            "steps": 0,
        }
        graph = self._build_graph(_RunContext())
        final_text = ""
        tool_calls = 0
        usage = {"inputTokens": 0, "outputTokens": 0}
        async for item in graph.astream(
            initial, stream_mode="custom", config={"recursion_limit": self._max_steps * 2 + 4}
        ):
            kind, data = item["type"], item["data"]
            if kind == "final":
                final_text = data["text"]
            elif kind == "usage":
                usage["inputTokens"] += data["inputTokens"]
                usage["outputTokens"] += data["outputTokens"]
            else:
                if kind == "tool_call" and data["status"] == "running":
                    tool_calls += 1
                yield AgentEvent(kind, data)
        yield AgentEvent(
            "done",
            {"text": final_text or STEP_LIMIT_TEXT, "toolCalls": tool_calls, "usage": usage},
        )

    # -- graph ------------------------------------------------------------------------------

    def _build_graph(self, ctx: _RunContext) -> Any:
        async def llm_node(state: AgentState) -> dict[str, Any]:
            return await self._llm_step(state)

        async def tools_node(state: AgentState) -> dict[str, Any]:
            last = state["messages"][-1]
            results = await asyncio.gather(*(self._run_tool(c, ctx) for c in last.tool_calls))
            return {"messages": list(results)}

        def route(state: AgentState) -> str:
            last = state["messages"][-1]
            return "tools" if last.tool_calls and state["steps"] < self._max_steps else END

        builder = StateGraph(AgentState)
        builder.add_node("llm", llm_node)
        builder.add_node("tools", tools_node)
        builder.add_edge(START, "llm")
        builder.add_conditional_edges("llm", route, {"tools": "tools", END: END})
        builder.add_edge("tools", "llm")
        return builder.compile()

    async def _llm_step(self, state: AgentState) -> dict[str, Any]:
        emit = get_stream_writer()
        started = time.perf_counter()
        parts: list[str] = []
        calls: list[ToolCall] = []
        usage = Usage(0, 0)
        async for event in self._llm.stream(state["messages"], CopilotTools.SPECS):
            if isinstance(event, TextDelta):
                parts.append(event.text)
                emit({"type": "token", "data": {"text": event.text}})
            elif isinstance(event, ToolUse):
                calls.append(event.call)
            elif isinstance(event, Usage):
                usage = event
        provider = self._llm.name
        LLM_LATENCY.labels(provider=provider).observe(time.perf_counter() - started)
        LLM_TOKENS.labels(provider=provider, direction="input").inc(usage.input_tokens)
        LLM_TOKENS.labels(provider=provider, direction="output").inc(usage.output_tokens)
        emit(
            {
                "type": "usage",
                "data": {"inputTokens": usage.input_tokens, "outputTokens": usage.output_tokens},
            }
        )
        text = "".join(parts)
        if not calls:
            emit({"type": "final", "data": {"text": text}})
        return {"messages": [Message("assistant", text, tuple(calls))], "steps": 1}

    async def _run_tool(self, call: ToolCall, ctx: _RunContext) -> Message:
        emit = get_stream_writer()
        emit(
            {
                "type": "tool_call",
                "data": {
                    "id": call.id,
                    "name": call.name,
                    "arguments": call.arguments,
                    "status": "running",
                },
            }
        )
        started = time.perf_counter()
        status = "ok"
        try:
            result = await self._tools.call(call.name, call.arguments)
        except ToolError as exc:
            status, result = "error", {"error": str(exc)}
        except Exception:
            log.exception("tool_crashed", tool=call.name)
            status, result = "error", {"error": f"{call.name} failed unexpectedly"}
        duration_ms = round((time.perf_counter() - started) * 1000, 1)
        if status == "ok" and call.name == "search_guidelines":
            self._number_and_emit_citations(result, ctx, emit)
        log.info("tool_called", tool=call.name, status=status, durationMs=duration_ms)
        emit(
            {
                "type": "tool_call",
                "data": {
                    "id": call.id,
                    "name": call.name,
                    "arguments": call.arguments,
                    "status": status,
                    "durationMs": duration_ms,
                    **({"error": result["error"]} if status == "error" else {}),
                },
            }
        )
        return Message("tool", json.dumps(result), tool_call_id=call.id, name=call.name)

    @staticmethod
    def _number_and_emit_citations(
        result: dict[str, Any], ctx: _RunContext, emit: Any
    ) -> None:
        for hit in result["results"]:
            chunk_id = hit["chunk_id"]
            is_new = chunk_id not in ctx.citations
            if is_new:
                ctx.citations[chunk_id] = len(ctx.citations) + 1
            hit["ref"] = ctx.citations[chunk_id]
            if is_new:
                emit(
                    {
                        "type": "citation",
                        "data": {
                            "index": hit["ref"],
                            "title": hit["title"],
                            "chunkId": chunk_id,
                            "documentId": hit["document_id"],
                            "score": hit["score"],
                            "snippet": " ".join(hit["content"].split())[:240],
                        },
                    }
                )
