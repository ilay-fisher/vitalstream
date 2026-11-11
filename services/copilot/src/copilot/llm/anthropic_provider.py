"""Anthropic Messages API adapter (streaming, tool use)."""

from collections.abc import AsyncIterator, Sequence
from typing import Any

from copilot.llm.types import LLMEvent, Message, TextDelta, ToolCall, ToolUse, Usage
from copilot.tools import ToolSpec


def to_api_messages(messages: Sequence[Message]) -> list[dict[str, Any]]:
    """Convert neutral messages to Anthropic format (system excluded, tool results grouped)."""
    api: list[dict[str, Any]] = []
    for message in messages:
        if message.role == "system":
            continue
        if message.role == "tool":
            block = {
                "type": "tool_result",
                "tool_use_id": message.tool_call_id,
                "content": message.content,
            }
            if api and api[-1]["role"] == "user" and isinstance(api[-1]["content"], list):
                api[-1]["content"].append(block)
            else:
                api.append({"role": "user", "content": [block]})
        elif message.role == "assistant" and message.tool_calls:
            blocks: list[dict[str, Any]] = []
            if message.content:
                blocks.append({"type": "text", "text": message.content})
            blocks += [
                {"type": "tool_use", "id": c.id, "name": c.name, "input": c.arguments}
                for c in message.tool_calls
            ]
            api.append({"role": "assistant", "content": blocks})
        else:
            api.append({"role": message.role, "content": message.content})
    return api


class AnthropicProvider:
    """Streams completions from Claude. The API key is held privately and never logged."""

    name = "anthropic"

    def __init__(
        self, api_key: str, model: str, max_tokens: int, client: Any | None = None
    ) -> None:
        self.model = model
        self._max_tokens = max_tokens
        self._client: Any
        if client is None:
            from anthropic import AsyncAnthropic

            client = AsyncAnthropic(api_key=api_key)
        self._client = client

    def __repr__(self) -> str:
        return f"AnthropicProvider(model={self.model!r})"

    async def stream(
        self, messages: Sequence[Message], tools: Sequence[ToolSpec]
    ) -> AsyncIterator[LLMEvent]:
        """Stream text deltas as they arrive, then tool calls and usage from the final message."""
        system = "\n\n".join(m.content for m in messages if m.role == "system")
        async with self._client.messages.stream(
            model=self.model,
            max_tokens=self._max_tokens,
            system=system,
            messages=to_api_messages(messages),
            tools=[
                {"name": t.name, "description": t.description, "input_schema": t.input_schema}
                for t in tools
            ],
        ) as stream:
            async for event in stream:
                if event.type == "text":
                    yield TextDelta(event.text)
            final = await stream.get_final_message()
        for block in final.content:
            if block.type == "tool_use":
                yield ToolUse(ToolCall(block.id, block.name, dict(block.input)))
        yield Usage(final.usage.input_tokens, final.usage.output_tokens)
