"""HTTP layer: SSE contract, documents, search, health, metrics, CORS, correlation, logging."""

import json
from collections.abc import AsyncIterator
from typing import Any

import httpx
import pytest
import respx
from fastapi import FastAPI

from copilot.agent.graph import AgentEvent
from copilot.agent.memory import ConversationMemory
from copilot.app import create_app
from copilot.config import Settings
from copilot.services import Services
from tests.conftest import ALERT_HIGH, CLINICAL, DETERIORATING


def make_services(stack: dict[str, Any], **overrides: Any) -> Services:
    services = Services(
        tools=stack["tools"],
        agent=stack["agent"],
        memory=ConversationMemory(ttl_seconds=60, max_sessions=10, max_messages=20),
        ingest=stack["ingest"],
        retriever=stack["retriever"],
    )
    for key, value in overrides.items():
        setattr(services, key, value)
    return services


@pytest.fixture
async def app(stack: dict[str, Any]) -> AsyncIterator[FastAPI]:
    yield create_app(Settings(), make_services(stack))


@pytest.fixture
async def client(app: FastAPI) -> AsyncIterator[httpx.AsyncClient]:
    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app=app), base_url="http://test"
    ) as c:
        yield c


def parse_sse(body: str) -> list[tuple[str, dict[str, Any]]]:
    events = []
    for block in body.strip().split("\n\n"):
        lines = block.split("\n")
        assert lines[0].startswith("event: ") and lines[1].startswith("data: "), block
        events.append((lines[0][7:], json.loads(lines[1][6:])))
    return events


def mock_clinical(router: respx.MockRouter) -> respx.Route:
    router.get(f"{CLINICAL}/api/v1/patients/P-0001/vitals").mock(
        return_value=httpx.Response(200, json=DETERIORATING)
    )
    return router.get(f"{CLINICAL}/api/v1/alerts").mock(
        return_value=httpx.Response(200, json={"content": [ALERT_HIGH]})
    )


async def test_chat_streams_documented_sse_events(
    client: httpx.AsyncClient, clinical_mock: respx.MockRouter
) -> None:
    mock_clinical(clinical_mock)
    response = await client.post(
        "/api/v1/chat",
        json={"sessionId": "s1", "message": "Is P-0001 deteriorating?"},
        headers={"X-Correlation-Id": "corr-chat-1"},
    )
    assert response.status_code == 200
    assert response.headers["content-type"].startswith("text/event-stream")
    assert response.headers["x-correlation-id"] == "corr-chat-1"

    events = parse_sse(response.text)
    names = [name for name, _ in events]
    assert set(names) == {"token", "tool_call", "citation", "done"}
    assert names[-1] == "done"
    assert names.index("tool_call") < names.index("citation") < names.index("token")
    done = events[-1][1]
    assert done["sessionId"] == "s1"
    assert done["correlationId"] == "corr-chat-1"
    assert done["text"] == "".join(d["text"] for n, d in events if n == "token")
    citation = next(d for n, d in events if n == "citation")
    assert {"index", "title", "chunkId", "documentId", "snippet"} <= citation.keys()


async def test_correlation_id_is_forwarded_to_clinical_api(
    client: httpx.AsyncClient, clinical_mock: respx.MockRouter
) -> None:
    route = mock_clinical(clinical_mock)
    await client.post(
        "/api/v1/chat",
        json={"sessionId": "s2", "patientId": "P-0001", "message": "status?"},
        headers={"X-Correlation-Id": "corr-fwd-9"},
    )
    assert route.calls.last.request.headers["X-Correlation-Id"] == "corr-fwd-9"


async def test_session_memory_carries_patient_between_turns(
    client: httpx.AsyncClient, clinical_mock: respx.MockRouter
) -> None:
    mock_clinical(clinical_mock)
    await client.post("/api/v1/chat", json={"sessionId": "mem", "message": "Check P-0001"})
    second = await client.post("/api/v1/chat", json={"sessionId": "mem", "message": "and now?"})
    calls = [d for n, d in parse_sse(second.text) if n == "tool_call" and d["status"] == "running"]
    assert calls[0]["arguments"]["patient_id"] == "P-0001"


async def test_chat_validates_request_with_problem_json(client: httpx.AsyncClient) -> None:
    response = await client.post("/api/v1/chat", json={"sessionId": "s", "patientId": "bad"})
    assert response.status_code == 422
    assert response.headers["content-type"].startswith("application/problem+json")
    body = response.json()
    assert body["status"] == 422
    assert {e["field"] for e in body["errors"]} >= {"patientId", "message"}


async def test_chat_failure_ends_stream_with_error_done(stack: dict[str, Any]) -> None:
    class Exploding:
        async def run(self, *_: Any) -> AsyncIterator[AgentEvent]:
            raise RuntimeError("boom")
            yield  # pragma: no cover

    application = create_app(Settings(), make_services(stack, agent=Exploding()))
    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app=application), base_url="http://t"
    ) as c:
        response = await c.post("/api/v1/chat", json={"sessionId": "x", "message": "hi"})
    events = parse_sse(response.text)
    assert events == [("done", {**events[0][1]})]
    assert events[0][1]["error"] is True


async def test_ingest_document_then_find_it_via_search(client: httpx.AsyncClient) -> None:
    created = await client.post(
        "/api/v1/documents",
        headers={"Authorization": "Bearer dev-admin-token"},
        json={
            "documents": [
                {
                    "title": "Ward falls rule",
                    "content": "# Ward falls rule\n\n## Night\n\nBed rails are mandatory at night.",
                }
            ]
        },
    )
    assert created.status_code == 201
    assert created.json()["documents"][0]["chunks"] >= 1

    found = await client.get(
        "/api/v1/documents/search", params={"q": "bed rails mandatory night", "k": 2}
    )
    body = found.json()
    assert found.status_code == 200
    assert len(body["results"]) == 2
    assert body["results"][0]["title"] == "Ward falls rule"
    assert body["results"][0]["bm25Rank"] == 1


async def test_ingest_blank_document_is_rejected(client: httpx.AsyncClient) -> None:
    response = await client.post(
        "/api/v1/documents",
        json={"documents": [{"content": "   "}]},
        headers={"Authorization": "Bearer dev-admin-token"},
    )
    assert response.status_code == 422
    assert response.headers["content-type"].startswith("application/problem+json")


@pytest.mark.parametrize("params", [{}, {"q": ""}, {"q": "x", "k": 0}, {"q": "x", "k": 99}])
async def test_search_validates_parameters(
    client: httpx.AsyncClient, params: dict[str, Any]
) -> None:
    assert (await client.get("/api/v1/documents/search", params=params)).status_code == 422


async def test_health_reports_up_and_degraded_and_down(stack: dict[str, Any]) -> None:
    async def ok() -> bool:
        return True

    async def bad() -> bool:
        return False

    async def check(checks: dict[str, Any]) -> tuple[int, dict[str, Any]]:
        application = create_app(
            Settings(),
            make_services(stack, health_checks=checks, critical_checks=frozenset({"pg"})),
        )
        async with httpx.AsyncClient(
            transport=httpx.ASGITransport(app=application), base_url="http://t"
        ) as c:
            r = await c.get("/health")
        return r.status_code, r.json()

    assert (await check({"pg": ok, "es": ok}))[1]["status"] == "UP"
    code, body = await check({"pg": ok, "es": bad})
    assert (code, body["status"]) == (200, "DEGRADED")
    code, body = await check({"pg": bad, "es": ok})
    assert (code, body["status"]) == (503, "DOWN")


async def test_metrics_exposes_business_counters(
    client: httpx.AsyncClient, clinical_mock: respx.MockRouter
) -> None:
    mock_clinical(clinical_mock)
    await client.post(
        "/api/v1/chat", json={"sessionId": "m", "message": "Is P-0001 deteriorating?"}
    )
    text = (await client.get("/metrics")).text
    assert 'copilot_chat_requests_total{status="ok"}' in text
    assert 'copilot_tool_calls_total{status="ok",tool="get_patient_vitals"}' in text
    assert "copilot_retrieval_latency_seconds_bucket" in text
    assert 'copilot_llm_latency_seconds_count{provider="fake"}' in text
    assert 'copilot_llm_tokens_total{direction="input",provider="fake"}' in text


async def test_cors_allows_dashboard_origins_only(client: httpx.AsyncClient) -> None:
    for origin in ("http://localhost:3000", "http://localhost:5173"):
        r = await client.options(
            "/api/v1/chat",
            headers={
                "Origin": origin,
                "Access-Control-Request-Method": "POST",
                "Access-Control-Request-Headers": "content-type,x-correlation-id",
            },
        )
        assert r.headers["access-control-allow-origin"] == origin
    denied = await client.options(
        "/api/v1/chat",
        headers={"Origin": "http://evil.example", "Access-Control-Request-Method": "POST"},
    )
    assert "access-control-allow-origin" not in denied.headers


async def test_correlation_id_generated_when_missing_or_unsafe(client: httpx.AsyncClient) -> None:
    generated = (await client.get("/health")).headers["x-correlation-id"]
    assert len(generated) == 36
    replaced = await client.get("/health", headers={"X-Correlation-Id": "bad id\twith spaces"})
    assert replaced.headers["x-correlation-id"] != "bad id\twith spaces"


async def test_request_log_is_json_with_service_and_correlation_id(
    client: httpx.AsyncClient, capsys: pytest.CaptureFixture[str]
) -> None:
    await client.get(
        "/api/v1/documents/search", params={"q": "fever"}, headers={"X-Correlation-Id": "log-77"}
    )
    lines = [
        json.loads(line) for line in capsys.readouterr().out.splitlines() if line.startswith("{")
    ]
    entry = next(e for e in lines if e["message"] == "request_completed")
    assert entry["service"] == "copilot"
    assert entry["correlationId"] == "log-77"
    assert entry["level"] == "info"
    assert entry["status"] == 200
    assert "timestamp" in entry
