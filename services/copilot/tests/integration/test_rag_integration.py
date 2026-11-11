"""Ingest → hybrid search against real pgvector and Elasticsearch."""

from pathlib import Path

import pytest
from elasticsearch import AsyncElasticsearch
from sqlalchemy import text
from sqlalchemy.ext.asyncio import AsyncEngine

from copilot.rag.es_index import EsKeywordIndex
from copilot.rag.ingest import IngestService
from copilot.rag.pg_store import PgStore
from copilot.rag.retriever import Retriever
from tests.integration.conftest import TEST_INDEX

pytestmark = pytest.mark.integration

CORPUS = Path(__file__).resolve().parents[2] / "corpus"
Rag = tuple[IngestService, Retriever, PgStore, EsKeywordIndex]


async def test_migration_creates_schema_tables_and_hnsw_cosine_index(engine: AsyncEngine) -> None:
    async with engine.connect() as conn:
        tables = (
            (
                await conn.execute(
                    text(
                        "SELECT table_name FROM information_schema.tables "
                        "WHERE table_schema='copilot'"
                    )
                )
            )
            .scalars()
            .all()
        )
        index_def = (
            await conn.execute(
                text("SELECT indexdef FROM pg_indexes WHERE indexname='ix_chunks_embedding_hnsw'")
            )
        ).scalar_one()
        dims = (
            await conn.execute(
                text(
                    "SELECT atttypmod FROM pg_attribute WHERE attrelid='copilot.chunks'::regclass "
                    "AND attname='embedding'"
                )
            )
        ).scalar_one()
    assert {"documents", "chunks"} <= set(tables)
    assert "hnsw" in index_def and "vector_cosine_ops" in index_def
    assert dims == 384


async def test_ingest_corpus_then_hybrid_search_ranks_right_document_first(rag: Rag) -> None:
    ingest, retriever, store, keyword = rag
    assert await ingest.ingest_corpus_if_empty(CORPUS) == 7
    assert await store.count_documents() == 7
    assert await keyword.count() > 7

    expectations = {
        "oxygen saturation below 92 needs supplemental oxygen": "Hypoxaemia management",
        "NEWS2 total of 7 emergency response critical care": "NEWS2 escalation protocol",
        "suspected infection blood cultures antibiotics within one hour": "Sepsis screening",
        "systolic blood pressure below 90 fluid bolus": "Hypotension management",
        "alarm fatigue non-actionable alarms": "Alarm fatigue policy",
    }
    for query, title in expectations.items():
        hits = await retriever.search(query, k=3)
        assert hits[0].title == title, query
        assert hits[0].vector_rank is not None
        assert hits[0].bm25_rank is not None


async def test_second_startup_does_not_reingest(rag: Rag) -> None:
    ingest, _, store, _ = rag
    await ingest.ingest_corpus_if_empty(CORPUS)
    assert await ingest.ingest_corpus_if_empty(CORPUS) == 0
    assert await store.count_documents() == 7


async def test_delete_cascades_and_keyword_index_can_be_rebuilt(
    rag: Rag, es_client: AsyncElasticsearch
) -> None:
    ingest, retriever, store, keyword = rag
    first = await ingest.ingest(
        title=None, content="# Doc one\n\n## A\n\nzebrafish protocol", source="t"
    )
    await ingest.ingest(title=None, content="# Doc two\n\n## B\n\nunrelated heart text", source="t")
    await store.delete_document(first.document_id)
    await keyword.delete_document(first.document_id)
    assert all(h.title != "Doc one" for h in await retriever.search("zebrafish", k=3))

    await es_client.delete_by_query(index=TEST_INDEX, query={"match_all": {}}, refresh=True)
    assert await keyword.count() == 0
    assert await ingest.reindex_keywords_if_empty() == 1
    assert await keyword.count() == 1


async def test_custom_document_is_searchable_immediately(rag: Rag) -> None:
    ingest, retriever, _, _ = rag
    await ingest.ingest(
        title="Local ward rule",
        content="# Local ward rule\n\n## Falls\n\nBed rails are required for night sedation.",
        source="api",
    )
    hits = await retriever.search("bed rails night sedation", k=2)
    assert hits[0].title == "Local ward rule"
