"""HTTP client for clinical-api (CONTRACTS §4), forwarding the correlation id."""

from datetime import UTC, datetime, timedelta
from typing import Any

import httpx
import structlog

from copilot.correlation import CORRELATION_HEADER, get_correlation_id

log = structlog.get_logger()
_LIST_KEYS = ("content", "items", "points", "vitals", "readings", "data", "alerts")


class ClinicalApiError(Exception):
    """clinical-api could not satisfy a request."""

    def __init__(self, message: str, *, not_found: bool = False) -> None:
        super().__init__(message)
        self.not_found = not_found


def _as_list(payload: Any) -> list[dict[str, Any]]:
    """Accept either a bare JSON array or a page object wrapping it."""
    if isinstance(payload, list):
        return [item for item in payload if isinstance(item, dict)]
    if isinstance(payload, dict):
        for key in _LIST_KEYS:
            value = payload.get(key)
            if isinstance(value, list):
                return [item for item in value if isinstance(item, dict)]
    return []


class ClinicalApiClient:
    """Thin async wrapper over the two clinical-api endpoints the copilot needs."""

    def __init__(self, base_url: str, timeout: float = 10.0) -> None:
        self._client = httpx.AsyncClient(base_url=base_url.rstrip("/"), timeout=timeout)

    async def aclose(self) -> None:
        """Release the connection pool."""
        await self._client.aclose()

    async def vitals(self, patient_id: str, minutes: int) -> list[dict[str, Any]]:
        """Raw vitals of the last ``minutes`` minutes for a patient."""
        now = datetime.now(UTC)
        params = {
            "from": (now - timedelta(minutes=minutes)).isoformat().replace("+00:00", "Z"),
            "to": now.isoformat().replace("+00:00", "Z"),
            "resolution": "raw",
        }
        return _as_list(
            await self._get(f"/api/v1/patients/{patient_id}/vitals", "patient_vitals", params)
        )

    async def alerts(
        self, patient_id: str | None, severity: str | None, size: int = 20
    ) -> list[dict[str, Any]]:
        """Alerts, newest first as served by clinical-api, optionally filtered."""
        params: dict[str, str | int] = {"size": size}
        if patient_id:
            params["patientId"] = patient_id
        if severity:
            params["severity"] = severity
        return _as_list(await self._get("/api/v1/alerts", "alerts", params))

    async def _get(self, path: str, endpoint: str, params: dict[str, Any]) -> Any:
        headers = {}
        if correlation_id := get_correlation_id():
            headers[CORRELATION_HEADER] = correlation_id
        try:
            response = await self._client.get(path, params=params, headers=headers)
        except httpx.HTTPError as exc:
            log.warning("clinical_api_unreachable", endpoint=endpoint, error=type(exc).__name__)
            raise ClinicalApiError("clinical-api unavailable") from exc
        if response.status_code == 404:
            raise ClinicalApiError("resource not found in clinical-api", not_found=True)
        if response.status_code >= 400:
            log.warning("clinical_api_error", endpoint=endpoint, status=response.status_code)
            raise ClinicalApiError(f"clinical-api unavailable (HTTP {response.status_code})")
        return response.json()
