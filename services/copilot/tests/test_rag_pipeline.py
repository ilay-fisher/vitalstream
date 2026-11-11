"""Embedder, ingestion and hybrid retrieval wired against in-memory adapters."""

import math
from pathlib import Path

import pytest

from copilot.rag.embedder import HashEmbedder
from copilot.rag.ingest import IngestService, extract_title
from copilot.rag.retriever import Retriever
from tests.fakes import FailingKeywordIndex, InMemoryKeywordIndex, InMemoryStore

SEPSIS = """# Sepsis screening

## Red flags

Fever above 38.3, tachycardia over 90 and raised respiratory rate suggest sepsis. Start
antibiotics and fluids within one hour.
"""
HYPOX = """# Hypoxaemia management

## Oxygen targets

Low oxygen saturation below 92 percent needs supplemental oxygen and urgent review of the
airway and breathing.
"""
FEVER = """# Fever pathway

## Antipyretics

Paracetamol for comfort; look for source of infection when temperature stays high.
"""


def test_hash_embedder_is_deterministic_normalised_and_384d() -> None:
    embedder = HashEmbedder()
    first = embedder.embed_query("low oxygen saturation")
    second = embedder.embed_query("low oxygen saturation")
    assert first == second
    assert len(first) == embedder.dimensions == 384
    assert math.isclose(math.sqrt(sum(x * x for x in first)), 1.0, rel_tol=1e-6)


def test_hash_embedder_places_related_text_closer() -> None:
    embedder = HashEmbedder()
    q = embedder.embed_query("low oxygen saturation")
    near, far = embedder.embed_documents(["oxygen saturation is low", "paracetamol dosing chart"])
    dot = lambda a, b: sum(x * y for x, y in zip(a, b, strict=True))  # noqa: E731
    assert dot(q, near) > dot(q, far)


def test_hash_embedder_handles_empty_text() -> None:
    vector = HashEmbedder().embed_query("   ")
    assert len(vector) == 384
    assert not any(math.isnan(x) for x in vector)


def test_extract_title_prefers_first_h1() -> None:
    assert extract_title("intro\n# Real Title\n## Sub", fallback="file") == "Real Title"
    assert extract_title("no heading", fallback="file") == "file"


async def _build() -> tuple[IngestService, Retriever, InMemoryStore, InMemoryKeywordIndex]:
    embedder = HashEmbedder()
    store = InMemoryStore()
    keyword = InMemoryKeywordIndex()
    ingest = IngestService(embedder, store, keyword)
    retriever = Retriever(embedder, store, keyword)
    return ingest, retriever, store, keyword


async def test_ingest_persists_chunks_and_indexes_keywords() -> None:
    ingest, _, store, keyword = await _build()
    result = await ingest.ingest(title=None, content=SEPSIS, source="unit")
    assert result.title == "Sepsis screening"
    assert result.chunks >= 1
    assert await store.count_documents() == 1
    assert await keyword.count() == result.chunks


async def test_ingest_rejects_blank_content() -> None:
    ingest, _, _, _ = await _build()
    with pytest.raises(ValueError, match="no content"):
        await ingest.ingest(title="x", content="  \n ", source="unit")


async def test_ingest_rolls_back_store_if_keyword_index_fails() -> None:
    embedder = HashEmbedder()
    store = InMemoryStore()

    class Broken(InMemoryKeywordIndex):
        async def index_chunks(self, chunks):  # type: ignore[no-untyped-def]
            raise ConnectionError("es down")

    ingest = IngestService(embedder, store, Broken())
    with pytest.raises(ConnectionError):
        await ingest.ingest(title="t", content=SEPSIS, source="unit")
    assert await store.count_documents() == 0


async def test_hybrid_search_ranks_the_relevant_document_first() -> None:
    ingest, retriever, _, _ = await _build()
    for text in (SEPSIS, HYPOX, FEVER):
        await ingest.ingest(title=None, content=text, source="unit")
    hits = await retriever.search("low oxygen saturation needs supplemental oxygen", k=3)
    assert hits[0].title == "Hypoxaemia management"
    assert hits[0].vector_rank == 1
    assert hits[0].bm25_rank == 1
    assert hits[0].score > hits[-1].score


async def test_search_returns_at_most_k_with_citation_fields() -> None:
    ingest, retriever, _, _ = await _build()
    for text in (SEPSIS, HYPOX, FEVER):
        await ingest.ingest(title=None, content=text, source="unit")
    hits = await retriever.search("fever", k=2)
    assert len(hits) == 2
    assert all(h.chunk_id and h.document_id and h.title for h in hits)


async def test_search_degrades_to_vector_only_when_keyword_side_fails() -> None:
    embedder = HashEmbedder()
    store = InMemoryStore()
    healthy = InMemoryKeywordIndex()
    ingest = IngestService(embedder, store, healthy)
    await ingest.ingest(title=None, content=HYPOX, source="unit")
    retriever = Retriever(embedder, store, FailingKeywordIndex())
    hits = await retriever.search("oxygen", k=3)
    assert hits
    assert all(h.bm25_rank is None for h in hits)


async def test_search_fails_when_both_sides_fail() -> None:
    class BrokenStore(InMemoryStore):
        async def search(self, embedding, k):  # type: ignore[no-untyped-def]
            raise ConnectionError("pg down")

    retriever = Retriever(HashEmbedder(), BrokenStore(), FailingKeywordIndex())
    with pytest.raises(ConnectionError):
        await retriever.search("anything", k=3)


async def test_blank_query_returns_nothing() -> None:
    _, retriever, _, _ = await _build()
    assert await retriever.search("   ", k=3) == []


async def test_corpus_ingested_only_when_store_empty(tmp_path: Path) -> None:
    (tmp_path / "a.md").write_text(SEPSIS)
    (tmp_path / "b.md").write_text(HYPOX)
    (tmp_path / "ignore.txt").write_text("nope")
    ingest, _, store, _ = await _build()
    assert await ingest.ingest_corpus_if_empty(tmp_path) == 2
    assert await ingest.ingest_corpus_if_empty(tmp_path) == 0
    assert await store.count_documents() == 2


async def test_keyword_index_rebuilt_from_store_when_empty() -> None:
    embedder = HashEmbedder()
    store = InMemoryStore()
    original = InMemoryKeywordIndex()
    await IngestService(embedder, store, original).ingest(title=None, content=SEPSIS, source="u")
    fresh = InMemoryKeywordIndex()
    rebuilt = await IngestService(embedder, store, fresh).reindex_keywords_if_empty()
    assert rebuilt == await original.count() == await fresh.count()
    assert await IngestService(embedder, store, fresh).reindex_keywords_if_empty() == 0
