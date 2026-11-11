"""Tool implementations: clinical-api access (mocked with respx), RAG, NEWS2, validation."""

import httpx
import pytest
import respx
from prometheus_client import REGISTRY

from copilot.clinical import ClinicalApiClient
from copilot.correlation import set_correlation_id
from copilot.rag.embedder import HashEmbedder
from copilot.rag.ingest import IngestService
from copilot.rag.retriever import Retriever
from copilot.tools import CopilotTools, ToolError
from tests.fakes import InMemoryKeywordIndex, InMemoryStore

BASE = "http://clinical.test"

VITALS = [
    {"metric": "HEART_RATE", "value": 131.0, "unit": "/min", "observedAt": "2026-11-11T20:15:30Z"},
    {"metric": "HEART_RATE", "value": 120.0, "unit": "/min", "observedAt": "2026-11-11T20:10:30Z"},
    {"metric": "SPO2", "value": 91.0, "unit": "%", "observedAt": "2026-11-11T20:15:30Z"},
    {"metric": "RESP_RATE", "value": 25.0, "unit": "/min", "observedAt": "2026-11-11T20:15:30Z"},
]
ALERTS_PAGE = {
    "content": [
        {
            "alertId": "0b7f9c3e-2a41-4f7e-9d0d-5c1a2b3c4d5e",
            "patientId": "P-0001",
            "type": "NEWS2",
            "severity": "HIGH",
            "score": 7.0,
            "message": "NEWS2 score 7",
            "status": "OPEN",
            "detectedAt": "2026-11-11T20:16:02Z",
        }
    ],
    "page": 0,
}


async def _tools() -> CopilotTools:
    embedder = HashEmbedder()
    store, keyword = InMemoryStore(), InMemoryKeywordIndex()
    await IngestService(embedder, store, keyword).ingest(
        title="NEWS2 escalation",
        content="# NEWS2 escalation\n\n## Scores\n\nA NEWS2 score of 7 needs emergency response.",
        source="unit",
    )
    clinical = ClinicalApiClient(BASE, timeout=2.0)
    return CopilotTools(clinical, Retriever(embedder, store, keyword))


@respx.mock
async def test_get_patient_vitals_summarises_latest_and_stats_and_forwards_correlation_id() -> None:
    route = respx.get(f"{BASE}/api/v1/patients/P-0001/vitals").mock(
        return_value=httpx.Response(200, json=VITALS)
    )
    set_correlation_id("corr-123")
    tools = await _tools()
    result = await tools.get_patient_vitals("P-0001", minutes=30)

    request = route.calls.last.request
    assert request.headers["X-Correlation-Id"] == "corr-123"
    assert request.url.params["resolution"] == "raw"
    assert "from" in request.url.params and "to" in request.url.params
    assert result["sample_count"] == 4
    assert result["latest"]["HEART_RATE"]["value"] == 131.0
    assert result["stats"]["HEART_RATE"] == {"min": 120.0, "max": 131.0, "avg": 125.5, "count": 2}
    set_correlation_id(None)


@respx.mock
async def test_get_patient_vitals_accepts_wrapped_payload_and_flags_empty() -> None:
    respx.get(f"{BASE}/api/v1/patients/P-0002/vitals").mock(
        return_value=httpx.Response(200, json={"items": []})
    )
    result = await (await _tools()).get_patient_vitals("P-0002")
    assert result["latest"] == {}
    assert "do not" in result["note"].lower()


@respx.mock
async def test_unknown_patient_maps_to_tool_error() -> None:
    respx.get(f"{BASE}/api/v1/patients/P-9999/vitals").mock(return_value=httpx.Response(404))
    with pytest.raises(ToolError, match="not found"):
        await (await _tools()).get_patient_vitals("P-9999")


@respx.mock
async def test_clinical_api_outage_maps_to_tool_error() -> None:
    respx.get(f"{BASE}/api/v1/patients/P-0001/vitals").mock(side_effect=httpx.ConnectError("boom"))
    with pytest.raises(ToolError, match="unavailable"):
        await (await _tools()).get_patient_vitals("P-0001")


@respx.mock
async def test_clinical_api_5xx_maps_to_tool_error() -> None:
    respx.get(f"{BASE}/api/v1/patients/P-0001/vitals").mock(return_value=httpx.Response(503))
    with pytest.raises(ToolError, match="unavailable"):
        await (await _tools()).get_patient_vitals("P-0001")


async def test_patient_id_is_validated_before_any_request() -> None:
    tools = await _tools()
    for bad in ("../admin", "P-1", "p-0001", "P-0001/../x"):
        with pytest.raises(ToolError, match="patient_id"):
            await tools.get_patient_vitals(bad)


@pytest.mark.parametrize("minutes", [0, -5, 100000])
async def test_minutes_must_be_in_range(minutes: int) -> None:
    with pytest.raises(ToolError, match="minutes"):
        await (await _tools()).get_patient_vitals("P-0001", minutes=minutes)


@respx.mock
async def test_list_alerts_normalises_alert_views_and_passes_filters() -> None:
    route = respx.get(f"{BASE}/api/v1/alerts").mock(
        return_value=httpx.Response(200, json=ALERTS_PAGE)
    )
    result = await (await _tools()).list_alerts(patient_id="P-0001", severity="HIGH")
    params = route.calls.last.request.url.params
    assert params["patientId"] == "P-0001"
    assert params["severity"] == "HIGH"
    assert result["count"] == 1
    assert result["alerts"][0]["alert_id"] == "0b7f9c3e-2a41-4f7e-9d0d-5c1a2b3c4d5e"
    assert result["alerts"][0]["severity"] == "HIGH"


@respx.mock
async def test_list_alerts_without_filters_and_bare_list_payload() -> None:
    route = respx.get(f"{BASE}/api/v1/alerts").mock(
        return_value=httpx.Response(200, json=ALERTS_PAGE["content"])
    )
    result = await (await _tools()).list_alerts()
    assert "patientId" not in route.calls.last.request.url.params
    assert result["count"] == 1


async def test_list_alerts_rejects_unknown_severity() -> None:
    with pytest.raises(ToolError, match="severity"):
        await (await _tools()).list_alerts(severity="URGENT")


async def test_search_guidelines_returns_citable_hits() -> None:
    result = await (await _tools()).search_guidelines("NEWS2 score of 7", k=3)
    hit = result["results"][0]
    assert hit["title"] == "NEWS2 escalation"
    assert hit["chunk_id"]
    assert "emergency" in hit["content"].lower()


@pytest.mark.parametrize("k", [0, 51])
async def test_search_guidelines_validates_k(k: int) -> None:
    with pytest.raises(ToolError, match="k"):
        await (await _tools()).search_guidelines("x", k=k)


async def test_compute_news2_tool_returns_breakdown() -> None:
    result = await (await _tools()).compute_news2(rr=25, spo2=91, sbp=120, hr=131, temp=37.0)
    assert result["total"] == 9
    assert result["risk"] == "HIGH"


async def test_compute_news2_tool_wraps_validation_errors() -> None:
    with pytest.raises(ToolError, match="plausible"):
        await (await _tools()).compute_news2(rr=-1, spo2=91, sbp=120, hr=131, temp=37.0)


async def test_call_dispatches_by_name_and_counts_metrics() -> None:
    tools = await _tools()
    before = (
        REGISTRY.get_sample_value(
            "copilot_tool_calls_total", {"tool": "compute_news2", "status": "ok"}
        )
        or 0.0
    )
    result = await tools.call(
        "compute_news2", {"rr": 16, "spo2": 98, "sbp": 120, "hr": 70, "temp": 37.0}
    )
    after = REGISTRY.get_sample_value(
        "copilot_tool_calls_total", {"tool": "compute_news2", "status": "ok"}
    )
    assert result["total"] == 0
    assert after == before + 1


async def test_call_unknown_tool_and_bad_arguments_raise_tool_error() -> None:
    tools = await _tools()
    with pytest.raises(ToolError, match="unknown tool"):
        await tools.call("drop_tables", {})
    with pytest.raises(ToolError, match="arguments"):
        await tools.call("compute_news2", {"rr": 16})
    errors = REGISTRY.get_sample_value(
        "copilot_tool_calls_total", {"tool": "compute_news2", "status": "error"}
    )
    assert errors and errors >= 1


def test_specs_describe_the_four_contract_tools() -> None:
    names = {spec.name for spec in CopilotTools.SPECS}
    assert names == {"get_patient_vitals", "list_alerts", "search_guidelines", "compute_news2"}
    for spec in CopilotTools.SPECS:
        assert spec.input_schema["type"] == "object"
        assert spec.description
