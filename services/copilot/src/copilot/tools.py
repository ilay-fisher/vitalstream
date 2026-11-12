"""The four copilot tools, implemented once and shared by the LangGraph agent and the MCP server."""

import re
from collections.abc import Awaitable, Callable
from dataclasses import dataclass
from typing import Any

from copilot.clinical import ClinicalApiClient, ClinicalApiError
from copilot.metrics import TOOL_CALLS
from copilot.news2 import compute_news2
from copilot.rag.retriever import Retriever

_PATIENT_ID = re.compile(r"^P-\d{4,}$")
_SEVERITIES = ("LOW", "MEDIUM", "HIGH", "CRITICAL")
_NO_VITALS_NOTE = (
    "No vitals were returned for this window. Do not infer or invent values; "
    "say that data is unavailable."
)


class ToolError(Exception):
    """A tool could not run; the message is safe to show to the model and the user."""


@dataclass(frozen=True, slots=True)
class ToolSpec:
    """Provider-neutral tool description (JSON Schema for the arguments)."""

    name: str
    description: str
    input_schema: dict[str, Any]


def _number(description: str) -> dict[str, Any]:
    return {"type": "number", "description": description}


class CopilotTools:
    """Tool implementations backed by clinical-api and the RAG retriever."""

    SPECS: tuple[ToolSpec, ...] = (
        ToolSpec(
            "get_patient_vitals",
            "Fetch a patient's recent vital signs from clinical-api: latest value and "
            "min/max/avg per metric over the window.",
            {
                "type": "object",
                "properties": {
                    "patient_id": {"type": "string", "description": "Patient id, e.g. P-0001"},
                    "minutes": {
                        "type": "integer",
                        "description": "Look-back window in minutes (1-1440, default 60)",
                    },
                },
                "required": ["patient_id"],
            },
        ),
        ToolSpec(
            "list_alerts",
            "List alerts raised by the stream processor, optionally for one patient and/or "
            "severity (LOW, MEDIUM, HIGH, CRITICAL).",
            {
                "type": "object",
                "properties": {
                    "patient_id": {"type": "string"},
                    "severity": {"type": "string", "enum": list(_SEVERITIES)},
                },
            },
        ),
        ToolSpec(
            "search_guidelines",
            "Hybrid (semantic + BM25) search over the clinical guideline corpus. Returns "
            "passages with document title and chunk id for citation.",
            {
                "type": "object",
                "properties": {
                    "query": {"type": "string"},
                    "k": {"type": "integer", "description": "Number of passages (1-50)"},
                },
                "required": ["query"],
            },
        ),
        ToolSpec(
            "compute_news2",
            "Compute the NEWS2 early-warning score from observed vitals. Only call with "
            "values that were actually measured.",
            {
                "type": "object",
                "properties": {
                    "rr": _number("Respiratory rate, /min"),
                    "spo2": _number("Oxygen saturation, %"),
                    "sbp": _number("Systolic blood pressure, mmHg"),
                    "hr": _number("Heart rate, /min"),
                    "temp": _number("Temperature, Celsius"),
                    "consciousness": {"type": "string", "description": "ACVPU, default A"},
                    "supplemental_o2": {"type": "boolean"},
                },
                "required": ["rr", "spo2", "sbp", "hr", "temp"],
            },
        ),
    )

    def __init__(self, clinical: ClinicalApiClient, retriever: Retriever) -> None:
        self._clinical = clinical
        self._retriever = retriever
        self._handlers: dict[str, Callable[..., Awaitable[dict[str, Any]]]] = {
            "get_patient_vitals": self.get_patient_vitals,
            "list_alerts": self.list_alerts,
            "search_guidelines": self.search_guidelines,
            "compute_news2": self.compute_news2,
        }

    @property
    def clinical(self) -> ClinicalApiClient:
        """The clinical-api client these tools use (its credential decides whose access applies)."""
        return self._clinical

    async def get_patient_vitals(self, patient_id: str, minutes: int = 60) -> dict[str, Any]:
        """Latest and aggregate vitals for a patient over the last ``minutes`` minutes."""
        self._check_patient_id(patient_id)
        if not 1 <= minutes <= 1440:
            raise ToolError("minutes must be between 1 and 1440")
        try:
            samples = await self._clinical.vitals(patient_id, minutes)
        except ClinicalApiError as exc:
            raise ToolError(self._describe(exc, f"patient {patient_id}")) from exc
        return self._summarise_vitals(patient_id, minutes, samples)

    async def list_alerts(
        self, patient_id: str | None = None, severity: str | None = None
    ) -> dict[str, Any]:
        """Alerts from clinical-api, normalised to snake_case."""
        if patient_id:
            self._check_patient_id(patient_id)
        if severity and severity.upper() not in _SEVERITIES:
            raise ToolError(f"severity must be one of {', '.join(_SEVERITIES)}")
        try:
            raw = await self._clinical.alerts(patient_id, severity.upper() if severity else None)
        except ClinicalApiError as exc:
            raise ToolError(self._describe(exc, "alerts")) from exc
        alerts = [
            {
                "alert_id": a.get("alertId") or a.get("id"),
                "patient_id": a.get("patientId"),
                "type": a.get("type"),
                "severity": a.get("severity"),
                "status": a.get("status"),
                "score": a.get("score"),
                "message": a.get("message"),
                "detected_at": a.get("detectedAt"),
            }
            for a in raw
        ]
        return {"count": len(alerts), "alerts": alerts}

    async def search_guidelines(self, query: str, k: int = 5) -> dict[str, Any]:
        """Hybrid search over the guideline corpus."""
        if not 1 <= k <= 50:
            raise ToolError("k must be between 1 and 50")
        hits = await self._retriever.search(query, k)
        return {
            "query": query,
            "results": [
                {
                    "chunk_id": h.chunk_id,
                    "document_id": h.document_id,
                    "title": h.title,
                    "content": h.content,
                    "provenance": h.provenance,
                    "score": round(h.score, 6),
                }
                for h in hits
            ],
        }

    async def compute_news2(
        self,
        rr: float,
        spo2: float,
        sbp: float,
        hr: float,
        temp: float,
        consciousness: str = "A",
        supplemental_o2: bool = False,
    ) -> dict[str, Any]:
        """NEWS2 score and risk band for the given observations."""
        try:
            result = compute_news2(
                rr=rr,
                spo2=spo2,
                sbp=sbp,
                hr=hr,
                temp=temp,
                consciousness=consciousness,
                supplemental_o2=supplemental_o2,
            )
        except ValueError as exc:
            raise ToolError(str(exc)) from exc
        return result.to_dict()

    async def call(self, name: str, arguments: dict[str, Any]) -> dict[str, Any]:
        """Dispatch a tool by name (used by the agent and MCP), counting outcomes."""
        handler = self._handlers.get(name)
        if handler is None:
            TOOL_CALLS.labels(tool=name[:40], status="error").inc()
            raise ToolError(f"unknown tool {name!r}")
        try:
            result = await handler(**arguments)
        except TypeError as exc:
            TOOL_CALLS.labels(tool=name, status="error").inc()
            raise ToolError(f"invalid arguments for {name}: {exc}") from exc
        except ToolError:
            TOOL_CALLS.labels(tool=name, status="error").inc()
            raise
        TOOL_CALLS.labels(tool=name, status="ok").inc()
        return result

    @staticmethod
    def _check_patient_id(patient_id: str) -> None:
        if not _PATIENT_ID.match(patient_id):
            raise ToolError("patient_id must look like P-0001")

    @staticmethod
    def _describe(exc: ClinicalApiError, subject: str) -> str:
        return f"{subject} not found" if exc.not_found else str(exc)

    @staticmethod
    def _summarise_vitals(
        patient_id: str, minutes: int, samples: list[dict[str, Any]]
    ) -> dict[str, Any]:
        by_metric: dict[str, list[dict[str, Any]]] = {}
        for sample in samples:
            metric, value = sample.get("metric"), sample.get("value")
            if isinstance(metric, str) and isinstance(value, int | float):
                by_metric.setdefault(metric, []).append(sample)
        latest: dict[str, Any] = {}
        stats: dict[str, Any] = {}
        for metric, items in by_metric.items():
            newest = max(items, key=lambda s: str(s.get("observedAt", "")))
            values = [float(s["value"]) for s in items]
            latest[metric] = {
                "value": newest["value"],
                "unit": newest.get("unit"),
                "observed_at": newest.get("observedAt"),
            }
            stats[metric] = {
                "min": min(values),
                "max": max(values),
                "avg": round(sum(values) / len(values), 2),
                "count": len(values),
            }
        result: dict[str, Any] = {
            "patient_id": patient_id,
            "window_minutes": minutes,
            "sample_count": sum(len(v) for v in by_metric.values()),
            "latest": latest,
            "stats": stats,
        }
        if not latest:
            result["note"] = _NO_VITALS_NOTE
        return result
