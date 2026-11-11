"""End-to-end agent flow with the scripted fake LLM; clinical-api mocked by respx."""

import re
from typing import Any

import httpx
import respx

from copilot.agent.graph import AgentEvent, CopilotAgent
from copilot.llm.types import Message
from tests.conftest import ALERT_HIGH, CLINICAL, DETERIORATING, STABLE


async def _run(
    agent: CopilotAgent,
    message: str,
    patient_id: str | None = None,
    history: list[Message] | None = None,
) -> list[AgentEvent]:
    return [e async for e in agent.run(history or [], message, patient_id)]


def _of(events: list[AgentEvent], kind: str) -> list[dict[str, Any]]:
    return [e.data for e in events if e.type == kind]


def _mock_patient(router: respx.MockRouter, vitals: list[dict[str, Any]], alerts: list[Any]) -> None:
    router.get(f"{CLINICAL}/api/v1/patients/P-0001/vitals").mock(
        return_value=httpx.Response(200, json=vitals)
    )
    router.get(f"{CLINICAL}/api/v1/alerts").mock(
        return_value=httpx.Response(200, json={"content": alerts})
    )


async def test_patient_question_runs_all_tools_and_cites_guidelines(
    stack: dict[str, Any], clinical_mock: respx.MockRouter
) -> None:
    _mock_patient(clinical_mock, DETERIORATING, [ALERT_HIGH])
    events = await _run(stack["agent"], "Is P-0001 deteriorating?")

    started = [d["name"] for d in _of(events, "tool_call") if d["status"] == "running"]
    assert started == ["get_patient_vitals", "list_alerts", "compute_news2", "search_guidelines"]

    citations = _of(events, "citation")
    assert citations
    assert all(c["title"] and c["chunkId"] and c["index"] >= 1 for c in citations)
    assert len({c["chunkId"] for c in citations}) == len(citations)

    answer = "".join(d["text"] for d in _of(events, "token"))
    assert "P-0001" in answer
    assert "NEWS2" in answer and "HIGH" in answer
    assert "131" in answer and "91" in answer
    assert "[1]" in answer
    assert "decision support" in answer.lower()

    done = _of(events, "done")[0]
    assert done["text"] == answer
    assert done["toolCalls"] == 4
    assert events[-1].type == "done"


async def test_stable_patient_gets_reassuring_but_cautious_answer(
    stack: dict[str, Any], clinical_mock: respx.MockRouter
) -> None:
    _mock_patient(clinical_mock, STABLE, [])
    events = await _run(stack["agent"], "How is P-0001 doing?")
    answer = "".join(d["text"] for d in _of(events, "token"))
    assert "NEWS2 is 0" in answer
    assert "no open alerts" in answer.lower()
    assert "deteriorat" in answer.lower()


async def test_patient_id_from_request_context_is_used(
    stack: dict[str, Any], clinical_mock: respx.MockRouter
) -> None:
    _mock_patient(clinical_mock, STABLE, [])
    events = await _run(stack["agent"], "Any concerns?", patient_id="P-0001")
    calls = [d for d in _of(events, "tool_call") if d["status"] == "running"]
    assert calls[0]["arguments"]["patient_id"] == "P-0001"


async def test_patient_id_is_remembered_from_history(
    stack: dict[str, Any], clinical_mock: respx.MockRouter
) -> None:
    _mock_patient(clinical_mock, STABLE, [])
    history = [Message(role="user", content="Check P-0001"), Message(role="assistant", content="ok")]
    events = await _run(stack["agent"], "and now?", history=history)
    assert _of(events, "tool_call")[0]["arguments"]["patient_id"] == "P-0001"


async def test_general_question_only_searches_guidelines(stack: dict[str, Any]) -> None:
    events = await _run(stack["agent"], "What does the NEWS2 escalation protocol say about a score of 7?")
    started = [d["name"] for d in _of(events, "tool_call") if d["status"] == "running"]
    assert started == ["search_guidelines"]
    answer = "".join(d["text"] for d in _of(events, "token"))
    assert "NEWS2 escalation protocol" in answer
    assert _of(events, "citation")[0]["title"] == "NEWS2 escalation protocol"


async def test_clinical_api_outage_is_reported_without_inventing_vitals(
    stack: dict[str, Any], clinical_mock: respx.MockRouter
) -> None:
    clinical_mock.get(f"{CLINICAL}/api/v1/patients/P-0001/vitals").mock(
        side_effect=httpx.ConnectError("down")
    )
    clinical_mock.get(f"{CLINICAL}/api/v1/alerts").mock(side_effect=httpx.ConnectError("down"))
    events = await _run(stack["agent"], "Is P-0001 deteriorating?")
    results = [d for d in _of(events, "tool_call") if d["status"] == "error"]
    assert {r["name"] for r in results} == {"get_patient_vitals", "list_alerts"}
    answer = "".join(d["text"] for d in _of(events, "token"))
    assert "could not retrieve" in answer.lower()
    assert not re.search(r"NEWS2 is \d", answer)
    assert events[-1].type == "done"


async def test_missing_vital_signs_skip_news2(
    stack: dict[str, Any], clinical_mock: respx.MockRouter
) -> None:
    partial = [v for v in DETERIORATING if v["metric"] != "TEMPERATURE"]
    _mock_patient(clinical_mock, partial, [])
    events = await _run(stack["agent"], "Is P-0001 deteriorating?")
    started = [d["name"] for d in _of(events, "tool_call") if d["status"] == "running"]
    assert "compute_news2" not in started
    answer = "".join(d["text"] for d in _of(events, "token"))
    assert "TEMPERATURE" in answer and "missing" in answer.lower()


async def test_step_budget_stops_runaway_tool_loops(
    stack: dict[str, Any], clinical_mock: respx.MockRouter
) -> None:
    _mock_patient(clinical_mock, DETERIORATING, [ALERT_HIGH])
    tight = CopilotAgent(stack["agent"]._llm, stack["tools"], max_steps=1)
    events = await _run(tight, "Is P-0001 deteriorating?")
    done = _of(events, "done")[0]
    assert "step limit" in done["text"].lower()
    assert events[-1].type == "done"
