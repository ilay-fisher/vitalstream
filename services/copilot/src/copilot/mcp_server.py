"""MCP server (official SDK, FastMCP) exposing the same tools as the agent over streamable HTTP."""

from collections.abc import Callable
from typing import Any

from mcp.server.fastmcp import FastMCP
from mcp.server.transport_security import TransportSecuritySettings

from copilot.tools import CopilotTools


def build_mcp(get_tools: Callable[[], CopilotTools], allowed_hosts: list[str]) -> FastMCP:
    """Create the MCP server.

    ``get_tools`` is resolved per call because the real tool objects only exist once the app's
    lifespan has connected to Postgres/Elasticsearch.  Stateless HTTP + JSON responses keep it
    trivially scalable and curl-friendly.  DNS-rebinding protection restricts the Host header
    to ``allowed_hosts``; the bearer token is enforced by `copilot.security.BearerGuard`.
    ``get_tools`` must return the tools backed by the SERVICE identity, never the chat tools.
    """
    server = FastMCP(
        "vitalstream-copilot",
        instructions=(
            "Clinical decision-support tools for VitalStream: patient vitals, alerts, "
            "guideline search and NEWS2. Synthetic data; not for real patient care."
        ),
        stateless_http=True,
        json_response=True,
        streamable_http_path="/mcp",
        transport_security=TransportSecuritySettings(
            enable_dns_rebinding_protection=True,
            allowed_hosts=allowed_hosts,
            allowed_origins=[f"http://{host}" for host in allowed_hosts],
        ),
    )

    @server.tool()
    async def get_patient_vitals(patient_id: str, minutes: int = 60) -> dict[str, Any]:
        """Latest and min/max/avg vital signs of a patient (e.g. P-0001) over the last N minutes."""
        return await get_tools().call(
            "get_patient_vitals", {"patient_id": patient_id, "minutes": minutes}
        )

    @server.tool()
    async def list_alerts(
        patient_id: str | None = None, severity: str | None = None
    ) -> dict[str, Any]:
        """List alerts, optionally filtered by patient and severity (LOW/MEDIUM/HIGH/CRITICAL)."""
        arguments = {"patient_id": patient_id, "severity": severity}
        return await get_tools().call("list_alerts", {k: v for k, v in arguments.items() if v})

    @server.tool()
    async def search_guidelines(query: str, k: int = 5) -> dict[str, Any]:
        """Hybrid semantic + BM25 search over clinical guidelines; returns citable passages."""
        return await get_tools().call("search_guidelines", {"query": query, "k": k})

    @server.tool()
    async def compute_news2(
        rr: float, spo2: float, sbp: float, hr: float, temp: float
    ) -> dict[str, Any]:
        """Compute NEWS2 (room-air scale 1, patient alert) from measured vital signs."""
        return await get_tools().call(
            "compute_news2", {"rr": rr, "spo2": spo2, "sbp": sbp, "hr": hr, "temp": temp}
        )

    return server
