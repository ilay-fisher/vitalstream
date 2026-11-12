"""Small pure builders and extractors shared by the e2e steps."""

from __future__ import annotations

from datetime import datetime
from typing import Any


def batch_body(
    patient: str, device: str, event_id: str, metric: str, value: float, observed_at: datetime
) -> dict[str, Any]:
    """Body of ``POST /api/v1/vitals/batch`` with a single reading (CONTRACTS section 4)."""
    return {
        "deviceId": device,
        "patientId": patient,
        "readings": [
            {
                "eventId": event_id,
                "metric": metric,
                "value": value,
                "observedAt": observed_at.strftime("%Y-%m-%dT%H:%M:%S.")
                + f"{observed_at.microsecond // 1000:03d}Z",
            }
        ],
    }


def ack_body(actor: str) -> dict[str, str]:
    """Body of ``POST /api/v1/alerts/{id}/ack``."""
    return {"by": actor, "note": "acknowledged by the e2e suite"}


def find_open_alert(page: dict[str, Any]) -> dict[str, Any] | None:
    """First OPEN alert in a ``GET /alerts`` page, or None."""
    return next((a for a in page.get("items", []) if a.get("status") == "OPEN"), None)


def tool_calls(events: list[tuple[str, dict[str, Any]]]) -> list[str]:
    """Names of copilot tool calls that completed successfully (``status: ok``)."""
    return [
        data["name"]
        for event, data in events
        if event == "tool_call" and data.get("status") == "ok"
    ]
