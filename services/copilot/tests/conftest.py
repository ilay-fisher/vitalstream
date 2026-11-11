"""Shared fixtures: a fully in-memory copilot stack with clinical-api mocked by respx."""

from collections.abc import AsyncIterator
from pathlib import Path

import pytest
import respx

from copilot.agent.graph import CopilotAgent
from copilot.clinical import ClinicalApiClient
from copilot.llm.fake import FakeLLM
from copilot.rag.embedder import HashEmbedder
from copilot.rag.ingest import IngestService
from copilot.rag.retriever import Retriever
from copilot.tools import CopilotTools
from tests.fakes import InMemoryKeywordIndex, InMemoryStore

CORPUS = Path(__file__).resolve().parents[1] / "corpus"
CLINICAL = "http://clinical.test"

DETERIORATING = [
    {"metric": "HEART_RATE", "value": 131.0, "unit": "/min", "observedAt": "2026-11-11T20:15:30Z"},
    {"metric": "HEART_RATE", "value": 118.0, "unit": "/min", "observedAt": "2026-11-11T20:05:30Z"},
    {"metric": "SPO2", "value": 91.0, "unit": "%", "observedAt": "2026-11-11T20:15:30Z"},
    {"metric": "RESP_RATE", "value": 25.0, "unit": "/min", "observedAt": "2026-11-11T20:15:30Z"},
    {"metric": "SYSTOLIC_BP", "value": 98.0, "unit": "mm[Hg]", "observedAt": "2026-11-11T20:15:30Z"},
    {"metric": "TEMPERATURE", "value": 38.6, "unit": "Cel", "observedAt": "2026-11-11T20:15:30Z"},
]
STABLE = [
    {"metric": "HEART_RATE", "value": 72.0, "unit": "/min", "observedAt": "2026-11-11T20:15:30Z"},
    {"metric": "SPO2", "value": 98.0, "unit": "%", "observedAt": "2026-11-11T20:15:30Z"},
    {"metric": "RESP_RATE", "value": 15.0, "unit": "/min", "observedAt": "2026-11-11T20:15:30Z"},
    {"metric": "SYSTOLIC_BP", "value": 125.0, "unit": "mm[Hg]", "observedAt": "2026-11-11T20:15:30Z"},
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


@pytest.fixture
def clinical_mock() -> respx.MockRouter:
    """respx router standing in for clinical-api."""
    with respx.mock(assert_all_called=False) as router:
        yield router


@pytest.fixture
async def stack() -> AsyncIterator[dict[str, object]]:
    """Embedder + in-memory stores loaded with the real synthetic corpus."""
    embedder = HashEmbedder()
    store, keyword = InMemoryStore(), InMemoryKeywordIndex()
    ingest = IngestService(embedder, store, keyword)
    await ingest.ingest_corpus_if_empty(CORPUS)
    retriever = Retriever(embedder, store, keyword)
    clinical = ClinicalApiClient(CLINICAL, timeout=2.0)
    tools = CopilotTools(clinical, retriever)
    yield {
        "embedder": embedder,
        "store": store,
        "keyword": keyword,
        "ingest": ingest,
        "retriever": retriever,
        "tools": tools,
        "agent": CopilotAgent(FakeLLM(), tools, max_steps=6),
    }
    await clinical.aclose()
