"""MCP server driven by the official MCP client over streamable HTTP (in-process ASGI)."""

import json
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from typing import Any

import httpx
import respx
from mcp import ClientSession
from mcp.client.streamable_http import streamablehttp_client

from copilot.app import create_app
from copilot.config import Settings
from tests.conftest import ALERT_HIGH, CLINICAL, DETERIORATING
from tests.test_api import make_services


@asynccontextmanager
async def mcp_session(stack: dict[str, Any]) -> AsyncIterator[ClientSession]:
    """MCP client session; entered inside each test so anyio scopes stay in one task."""
    app = create_app(Settings(), make_services(stack))

    def factory(
        headers: dict[str, str] | None = None,
        timeout: httpx.Timeout | None = None,
        auth: httpx.Auth | None = None,
    ) -> httpx.AsyncClient:
        return httpx.AsyncClient(
            transport=httpx.ASGITransport(app=app),
            headers=headers,
            timeout=timeout,
            auth=auth,
            follow_redirects=True,
        )

    async with (
        app.router.lifespan_context(app),
        streamablehttp_client(
            "http://localhost:8083/mcp",
            headers={"Authorization": "Bearer dev-mcp-token"},
            httpx_client_factory=factory,
        ) as (read, write, _),
        ClientSession(read, write) as session,
    ):
        await session.initialize()
        yield session


def payload(result: Any) -> dict[str, Any]:
    assert not result.isError, result.content
    data: dict[str, Any] = json.loads(result.content[0].text)
    return data


async def test_lists_the_four_contract_tools_with_schemas(stack: dict[str, Any]) -> None:
    async with mcp_session(stack) as session:
        tools = (await session.list_tools()).tools
    by_name = {t.name: t for t in tools}
    assert set(by_name) == {
        "get_patient_vitals",
        "list_alerts",
        "search_guidelines",
        "compute_news2",
    }
    assert by_name["get_patient_vitals"].inputSchema["required"] == ["patient_id"]
    required = set(by_name["compute_news2"].inputSchema["required"])
    assert required == {"rr", "spo2", "sbp", "hr", "temp"}
    assert all(t.description for t in tools)


async def test_compute_news2_tool(stack: dict[str, Any]) -> None:
    async with mcp_session(stack) as session:
        result = await session.call_tool(
            "compute_news2", {"rr": 25, "spo2": 91, "sbp": 120, "hr": 131, "temp": 37.0}
        )
    data = payload(result)
    assert data["total"] == 9
    assert data["risk"] == "HIGH"


async def test_search_guidelines_tool_returns_citable_passages(stack: dict[str, Any]) -> None:
    async with mcp_session(stack) as session:
        result = await session.call_tool(
            "search_guidelines", {"query": "hypoxaemia oxygen", "k": 2}
        )
    data = payload(result)
    assert len(data["results"]) == 2
    assert data["results"][0]["title"] == "Hypoxaemia management"
    assert data["results"][0]["chunk_id"]


async def test_vitals_and_alert_tools_use_clinical_api(
    stack: dict[str, Any], clinical_mock: respx.MockRouter
) -> None:
    vitals_route = clinical_mock.get(f"{CLINICAL}/api/v1/patients/P-0001/vitals").mock(
        return_value=httpx.Response(200, json=DETERIORATING)
    )
    alerts_route = clinical_mock.get(f"{CLINICAL}/api/v1/alerts").mock(
        return_value=httpx.Response(200, json={"content": [ALERT_HIGH]})
    )
    async with mcp_session(stack) as session:
        vitals = payload(
            await session.call_tool("get_patient_vitals", {"patient_id": "P-0001", "minutes": 30})
        )
        alerts = payload(
            await session.call_tool("list_alerts", {"patient_id": "P-0001", "severity": "HIGH"})
        )
    assert vitals["latest"]["HEART_RATE"]["value"] == 131.0
    assert alerts["count"] == 1
    for route in (vitals_route, alerts_route):
        assert route.calls.last.request.headers["Authorization"] == "Bearer test-service-token"


async def test_tool_errors_surface_as_mcp_errors(stack: dict[str, Any]) -> None:
    async with mcp_session(stack) as session:
        result = await session.call_tool("get_patient_vitals", {"patient_id": "../etc/passwd"})
    assert result.isError
    assert "patient_id" in result.content[0].text
