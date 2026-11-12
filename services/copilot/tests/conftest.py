"""Shared fixtures: a fully in-memory copilot stack with clinical-api mocked by respx."""

from collections.abc import AsyncIterator, Iterator
from pathlib import Path

import httpx
import pytest
import respx

from copilot.agent.graph import CopilotAgent
from copilot.clinical import ClinicalApiClient
from copilot.clinical_auth import StaticTokenProvider, UserTokenProvider
from copilot.llm.fake import FakeLLM
from copilot.rag.embedder import HashEmbedder
from copilot.rag.ingest import IngestService
from copilot.rag.retriever import Retriever
from copilot.tools import CopilotTools
from copilot.user_context import set_user_token
from tests.fakes import InMemoryKeywordIndex, InMemoryStore

CORPUS = Path(__file__).resolve().parents[1] / "corpus"
CLINICAL = "http://clinical.test"
SERVICE_TOKEN = "test-service-token"
USER_TOKEN = "test-user-token"
USER_HEADERS = {"Authorization": f"Bearer {USER_TOKEN}"}

DETERIORATING = [
    {"metric": "HEART_RATE", "value": 131.0, "unit": "/min", "observedAt": "2026-11-11T20:15:30Z"},
    {"metric": "HEART_RATE", "value": 118.0, "unit": "/min", "observedAt": "2026-11-11T20:05:30Z"},
    {"metric": "SPO2", "value": 91.0, "unit": "%", "observedAt": "2026-11-11T20:15:30Z"},
    {"metric": "RESP_RATE", "value": 25.0, "unit": "/min", "observedAt": "2026-11-11T20:15:30Z"},
    {
        "metric": "SYSTOLIC_BP",
        "value": 98.0,
        "unit": "mm[Hg]",
        "observedAt": "2026-11-11T20:15:30Z",
    },
    {"metric": "TEMPERATURE", "value": 38.6, "unit": "Cel", "observedAt": "2026-11-11T20:15:30Z"},
]
STABLE = [
    {"metric": "HEART_RATE", "value": 72.0, "unit": "/min", "observedAt": "2026-11-11T20:15:30Z"},
    {"metric": "SPO2", "value": 98.0, "unit": "%", "observedAt": "2026-11-11T20:15:30Z"},
    {"metric": "RESP_RATE", "value": 15.0, "unit": "/min", "observedAt": "2026-11-11T20:15:30Z"},
    {
        "metric": "SYSTOLIC_BP",
        "value": 125.0,
        "unit": "mm[Hg]",
        "observedAt": "2026-11-11T20:15:30Z",
    },
    {"metric": "TEMPERATURE", "value": 36.9, "unit": "Cel", "observedAt": "2026-11-11T20:15:30Z"},
]
ALERT_HIGH = {
    "alertId": "0b7f9c3e-2a41-4f7e-9d0d-5c1a2b3c4d5e",
    "patientId": "P-0001",
    "type": "NEWS2",
    "severity": "HIGH",
    "score": 7.0,
    "message": "NEWS2 score 7 (RR 25, SpO2 91, HR 131)",
    "status": "OPEN",
    "detectedAt": "2026-11-11T20:16:02Z",
}


@pytest.fixture(autouse=True)
def user_token() -> Iterator[None]:
    """Agent tests run "inside a chat request": the user's token is in the request context."""
    set_user_token(USER_TOKEN)
    yield
    set_user_token(None)


@pytest.fixture
def clinical_mock() -> respx.MockRouter:
    """respx router standing in for clinical-api; ``/me`` accepts any token as nurse.levi."""
    with respx.mock(assert_all_called=False) as router:
        router.get(f"{CLINICAL}/api/v1/me").mock(
            return_value=httpx.Response(
                200, json={"sub": "nurse.levi", "name": "Nurse Levi", "roles": ["CLINICIAN"]}
            )
        )
        yield router


@pytest.fixture
async def stack() -> AsyncIterator[dict[str, object]]:
    """Embedder + in-memory stores loaded with the real synthetic corpus."""
    embedder = HashEmbedder()
    store, keyword = InMemoryStore(), InMemoryKeywordIndex()
    ingest = IngestService(embedder, store, keyword)
    await ingest.ingest_corpus_if_empty(CORPUS)
    retriever = Retriever(embedder, store, keyword)
    # Two clients, as in production: chat tools use the user's token, MCP tools the SERVICE token.
    user_clinical = ClinicalApiClient(CLINICAL, UserTokenProvider(), timeout=2.0)
    service_clinical = ClinicalApiClient(CLINICAL, StaticTokenProvider(SERVICE_TOKEN), timeout=2.0)
    tools = CopilotTools(user_clinical, retriever)
    yield {
        "embedder": embedder,
        "store": store,
        "keyword": keyword,
        "ingest": ingest,
        "retriever": retriever,
        "tools": tools,
        "mcp_tools": CopilotTools(service_clinical, retriever),
        "agent": CopilotAgent(FakeLLM(), tools, max_steps=6),
    }
    await user_clinical.aclose()
    await service_clinical.aclose()
