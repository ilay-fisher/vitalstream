"""Chat authenticates the end user; tools act with that user's token (no confused deputy)."""

import asyncio
from typing import Any

import httpx
import pytest
import respx

from copilot.agent.graph import CopilotAgent
from copilot.app import create_app
from copilot.config import Settings
from copilot.user_context import get_user_token, set_user_token
from tests.conftest import ALERT_HIGH, CLINICAL, DETERIORATING, SERVICE_TOKEN
from tests.test_api import make_services, parse_sse
from tests.test_security import Recorder

IDENTITIES = {
    "tok-levi": {"sub": "nurse.levi", "name": "Nurse Levi", "roles": ["CLINICIAN"]},
    "tok-cohen": {"sub": "dr.cohen", "name": "Dr. Cohen", "roles": ["CLINICIAN"]},
    "tok-observer": {"sub": "observer", "name": "Observer", "roles": ["VIEWER"]},
}


def mock_clinical_for_users(router: respx.MockRouter) -> dict[str, respx.Route]:
    """/me answers by token (401 for unknown, 403 for ``tok-forbidden``); data routes always 200."""

    def me(request: httpx.Request) -> httpx.Response:
        token = request.headers.get("Authorization", "").removeprefix("Bearer ")
        if token == "tok-forbidden":
            return httpx.Response(403, json={"status": 403})
        if token in IDENTITIES:
            return httpx.Response(200, json=IDENTITIES[token])
        return httpx.Response(401, json={"status": 401})

    return {
        "me": router.get(f"{CLINICAL}/api/v1/me").mock(side_effect=me),
        "vitals": router.get(f"{CLINICAL}/api/v1/patients/P-0001/vitals").mock(
            return_value=httpx.Response(200, json=DETERIORATING)
        ),
        "alerts": router.get(f"{CLINICAL}/api/v1/alerts").mock(
            return_value=httpx.Response(200, json={"content": [ALERT_HIGH]})
        ),
    }


def build(stack: dict[str, Any]) -> tuple[httpx.AsyncClient, Recorder]:
    recorder = Recorder()
    services = make_services(stack, agent=CopilotAgent(recorder, stack["tools"], max_steps=6))
    app = create_app(Settings(), services)
    client = httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://localhost")
    return client, recorder


def bearer(token: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {token}"}


CHAT = {"patientId": "P-0001", "message": "Summarise this patient"}


async def test_chat_without_a_token_is_401_and_nothing_runs(
    stack: dict[str, Any], clinical_mock: respx.MockRouter
) -> None:
    routes = mock_clinical_for_users(clinical_mock)
    client, recorder = build(stack)
    async with client:
        response = await client.post("/api/v1/chat", json=CHAT)
    assert response.status_code == 401
    assert response.headers["content-type"].startswith("application/problem+json")
    assert response.headers["www-authenticate"].startswith("Bearer")
    assert recorder.seen == []
    assert not any(r.called for r in routes.values())


@pytest.mark.parametrize("header", ["Basic dXNlcjpwdw==", "Bearer", "Bearer  ", "token tok-levi"])
async def test_malformed_authorization_is_401(
    stack: dict[str, Any], clinical_mock: respx.MockRouter, header: str
) -> None:
    mock_clinical_for_users(clinical_mock)
    client, recorder = build(stack)
    async with client:
        response = await client.post("/api/v1/chat", json=CHAT, headers={"Authorization": header})
    assert response.status_code == 401
    assert recorder.seen == []


@pytest.mark.parametrize(("token", "status"), [("tok-unknown", 401), ("tok-forbidden", 403)])
async def test_token_rejected_by_clinical_api_gives_the_same_status_and_no_llm_call(
    stack: dict[str, Any], clinical_mock: respx.MockRouter, token: str, status: int
) -> None:
    routes = mock_clinical_for_users(clinical_mock)
    client, recorder = build(stack)
    async with client:
        response = await client.post("/api/v1/chat", json=CHAT, headers=bearer(token))
    assert response.status_code == status
    assert response.headers["content-type"].startswith("application/problem+json")
    assert recorder.seen == []
    assert not routes["vitals"].called and not routes["alerts"].called


async def test_clinical_api_down_during_validation_is_503_without_an_llm_call(
    stack: dict[str, Any], clinical_mock: respx.MockRouter
) -> None:
    clinical_mock.get(f"{CLINICAL}/api/v1/me").mock(side_effect=httpx.ConnectError("down"))
    client, recorder = build(stack)
    async with client:
        response = await client.post("/api/v1/chat", json=CHAT, headers=bearer("tok-levi"))
    assert response.status_code == 503
    assert recorder.seen == []


async def test_a_viewer_can_chat_and_every_tool_call_carries_the_viewer_token(
    stack: dict[str, Any], clinical_mock: respx.MockRouter
) -> None:
    routes = mock_clinical_for_users(clinical_mock)
    client, recorder = build(stack)
    async with client:
        response = await client.post("/api/v1/chat", json=CHAT, headers=bearer("tok-observer"))
    events = parse_sse(response.text)
    assert response.status_code == 200
    assert events[-1][0] == "done" and not events[-1][1].get("error")
    assert recorder.seen
    for name in ("me", "vitals", "alerts"):
        assert routes[name].called, name
        for call in routes[name].calls:
            assert call.request.headers["Authorization"] == "Bearer tok-observer"
    assert all(
        SERVICE_TOKEN not in call.request.headers["Authorization"]
        for route in routes.values()
        for call in route.calls
    )


async def test_concurrent_chats_never_mix_tokens(
    stack: dict[str, Any], clinical_mock: respx.MockRouter
) -> None:
    routes = mock_clinical_for_users(clinical_mock)
    client, _ = build(stack)
    async with client:
        await asyncio.gather(
            *(
                client.post("/api/v1/chat", json=CHAT, headers=bearer(token))
                for token in ("tok-levi", "tok-observer", "tok-cohen") * 3
            )
        )
    seen = [c.request.headers["Authorization"] for c in routes["vitals"].calls]
    assert sorted(seen) == sorted(
        f"Bearer {t}" for t in ("tok-levi", "tok-observer", "tok-cohen") * 3
    )


async def test_session_is_bound_to_the_sub(
    stack: dict[str, Any], clinical_mock: respx.MockRouter
) -> None:
    mock_clinical_for_users(clinical_mock)
    client, _ = build(stack)
    async with client:
        first = await client.post("/api/v1/chat", json=CHAT, headers=bearer("tok-levi"))
        sid = parse_sse(first.text)[0][1]["sessionId"]
        same = await client.post(
            "/api/v1/chat", json={**CHAT, "sessionId": sid}, headers=bearer("tok-levi")
        )
        other = await client.post(
            "/api/v1/chat", json={**CHAT, "sessionId": sid}, headers=bearer("tok-observer")
        )
        back = await client.post(
            "/api/v1/chat", json={**CHAT, "sessionId": sid}, headers=bearer("tok-levi")
        )
    assert parse_sse(same.text)[0][1]["sessionId"] == sid
    assert parse_sse(other.text)[0][1]["sessionId"] != sid
    assert parse_sse(back.text)[0][1]["sessionId"] == sid


async def test_the_token_never_reaches_logs_prompt_tool_arguments_or_events(
    stack: dict[str, Any],
    clinical_mock: respx.MockRouter,
    capsys: pytest.CaptureFixture[str],
) -> None:
    mock_clinical_for_users(clinical_mock)
    client, recorder = build(stack)
    async with client:
        response = await client.post("/api/v1/chat", json=CHAT, headers=bearer("tok-observer"))
        await client.post("/api/v1/chat", json=CHAT, headers=bearer("tok-unknown"))
    shown = repr(recorder.seen)
    out = capsys.readouterr()
    for token in ("tok-observer", "tok-unknown"):
        assert token not in shown
        assert token not in response.text
        assert token not in out.out + out.err


async def test_user_token_context_is_reset_after_the_request(
    stack: dict[str, Any], clinical_mock: respx.MockRouter
) -> None:
    mock_clinical_for_users(clinical_mock)
    set_user_token(None)
    client, _ = build(stack)
    async with client:
        await client.post("/api/v1/chat", json=CHAT, headers=bearer("tok-levi"))
    assert get_user_token() is None
