"""Composition root: wires adapters, retrieval, tools and the agent from settings."""

from collections.abc import Awaitable, Callable
from dataclasses import dataclass, field

import httpx
import structlog
from elasticsearch import AsyncElasticsearch
from sqlalchemy import text

from copilot.agent.graph import CopilotAgent
from copilot.agent.memory import ConversationMemory
from copilot.clinical import ClinicalApiClient
from copilot.clinical_auth import build_token_provider
from copilot.config import Settings
from copilot.db import create_engine, run_migrations, session_factory
from copilot.llm.factory import build_llm
from copilot.rag.embedder import build_embedder
from copilot.rag.es_index import EsKeywordIndex
from copilot.rag.ingest import IngestService
from copilot.rag.pg_store import PgStore
from copilot.rag.retriever import Retriever
from copilot.tools import CopilotTools

log = structlog.get_logger()


@dataclass
class Services:
    """Everything the HTTP layer and the MCP server need at runtime."""

    tools: CopilotTools
    agent: CopilotAgent
    memory: ConversationMemory
    ingest: IngestService
    retriever: Retriever
    llm_provider: str = "fake"
    health_checks: dict[str, Callable[[], Awaitable[bool]]] = field(default_factory=dict)
    critical_checks: frozenset[str] = frozenset()
    closers: list[Callable[[], Awaitable[None]]] = field(default_factory=list)

    async def aclose(self) -> None:
        """Release network resources."""
        for closer in reversed(self.closers):
            await closer()


async def build_services(settings: Settings) -> Services:
    """Create real services: migrate Postgres, prepare ES, ingest the corpus when empty."""
    await run_migrations(settings.sqlalchemy_url)
    engine = create_engine(settings.sqlalchemy_url)
    es_client = AsyncElasticsearch(settings.elasticsearch_url)
    clinical_http = httpx.AsyncClient(
        base_url=settings.clinical_api_url.rstrip("/"), timeout=settings.http_timeout_seconds
    )
    clinical = ClinicalApiClient(
        settings.clinical_api_url,
        build_token_provider(settings, clinical_http),
        settings.http_timeout_seconds,
        http_client=clinical_http,
    )

    store = PgStore(session_factory(engine))
    keyword = EsKeywordIndex(es_client, settings.es_index)
    embedder = build_embedder(settings)
    ingest = IngestService(
        embedder,
        store,
        keyword,
        chunk_tokens=settings.chunk_tokens,
        overlap_tokens=settings.chunk_overlap_tokens,
    )
    retriever = Retriever(
        embedder, store, keyword, rrf_k=settings.rrf_k, candidates=settings.retrieval_candidates
    )
    tools = CopilotTools(clinical, retriever)
    llm = build_llm(settings)

    await keyword.ensure_index()
    await ingest.reindex_keywords_if_empty()
    if settings.ingest_on_startup:
        await ingest.ingest_corpus_if_empty(settings.corpus_dir)

    async def postgres_ok() -> bool:
        async with engine.connect() as conn:
            await conn.execute(text("SELECT 1"))
        return True

    async def elasticsearch_ok() -> bool:
        return bool(await es_client.ping())

    log.info("services_ready", llmProvider=llm.name, embedder=settings.embedder)
    return Services(
        tools=tools,
        agent=CopilotAgent(llm, tools, max_steps=settings.max_agent_steps),
        memory=ConversationMemory(
            ttl_seconds=settings.session_ttl_seconds,
            max_sessions=settings.max_sessions,
            max_messages=settings.max_history_messages,
        ),
        ingest=ingest,
        retriever=retriever,
        llm_provider=llm.name,
        health_checks={"postgres": postgres_ok, "elasticsearch": elasticsearch_ok},
        critical_checks=frozenset({"postgres"}),
        closers=[engine.dispose, es_client.close, clinical.aclose],
    )
