"""clinical-api wraps vitals in an object with a ``points`` array (CONTRACTS section 4)."""

import httpx
import pytest
import respx

from copilot.clinical import ClinicalApiClient
from copilot.rag.embedder import HashEmbedder
from copilot.rag.retriever import Retriever
from copilot.tools import CopilotTools
from tests.fakes import InMemoryKeywordIndex, InMemoryStore

BASE = "http://clinical.test"
POINT = {
    "observedAt": "2026-11-11T20:15:30Z",
    "metric": "HEART_RATE",
    "value": 88.0,
    "unit": "bpm",
    "deviceId": "dev-1",
}
VITALS_OBJECT = {
    "patientId": "P-0001",
    "resolution": "RAW",
    "from": "2026-11-11T19:15:30Z",
    "to": "2026-11-11T20:15:30Z",
    "points": [POINT],
}


@pytest.mark.asyncio
async def test_vitals_unwraps_points_object() -> None:
    client = ClinicalApiClient(BASE, timeout=2.0)
    with respx.mock(assert_all_called=False) as router:
        router.get(f"{BASE}/api/v1/patients/P-0001/vitals").mock(
            return_value=httpx.Response(200, json=VITALS_OBJECT)
        )
        points = await client.vitals("P-0001", 60)
    await client.aclose()
    assert points == [POINT]


@pytest.mark.asyncio
async def test_latest_vitals_tool_reads_points_object() -> None:
    clinical = ClinicalApiClient(BASE, timeout=2.0)
    embedder = HashEmbedder()
    store, keyword = InMemoryStore(), InMemoryKeywordIndex()
    tools = CopilotTools(clinical, Retriever(embedder, store, keyword))
    with respx.mock(assert_all_called=False) as router:
        router.get(f"{BASE}/api/v1/patients/P-0001/vitals").mock(
            return_value=httpx.Response(200, json=VITALS_OBJECT)
        )
        result = await tools.get_patient_vitals("P-0001", 60)
    await clinical.aclose()
    assert result["latest"]["HEART_RATE"] == {
        "value": 88.0,
        "unit": "bpm",
        "observed_at": "2026-11-11T20:15:30Z",
    }
    assert result["sample_count"] == 1
