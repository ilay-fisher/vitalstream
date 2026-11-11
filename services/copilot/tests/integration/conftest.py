"""Testcontainers: pgvector Postgres and Elasticsearch shared by the whole integration session."""

import time
from collections.abc import AsyncIterator, Iterator

import httpx
import pytest
from elasticsearch import AsyncElasticsearch
from sqlalchemy import text
from sqlalchemy.ext.asyncio import AsyncEngine
from testcontainers.core.container import DockerContainer
from testcontainers.postgres import PostgresContainer

from copilot.db import create_engine, run_migrations, session_factory
from copilot.rag.embedder import HashEmbedder
from copilot.rag.es_index import EsKeywordIndex
from copilot.rag.ingest import IngestService
from copilot.rag.pg_store import PgStore
from copilot.rag.retriever import Retriever

ES_IMAGE = "docker.elastic.co/elasticsearch/elasticsearch:8.15.3"
TEST_INDEX = "guidelines-it"


@pytest.fixture(scope="session")
def postgres_url() -> Iterator[str]:
    """SQLAlchemy asyncpg URL of a throwaway pgvector Postgres."""
    with PostgresContainer(
        "pgvector/pgvector:pg16",
        username="vitalstream",
        password="vitalstream",
        dbname="vitalstream",
    ) as pg:
        host, port = pg.get_container_host_ip(), pg.get_exposed_port(5432)
        yield f"postgresql+asyncpg://vitalstream:vitalstream@{host}:{port}/vitalstream"


@pytest.fixture(scope="session")
def elasticsearch_url() -> Iterator[str]:
    """URL of a throwaway single-node Elasticsearch (security off, 512 MB heap)."""
    container = (
        DockerContainer(ES_IMAGE)
        .with_env("discovery.type", "single-node")
        .with_env("xpack.security.enabled", "false")
        .with_env("ES_JAVA_OPTS", "-Xms512m -Xmx512m")
        .with_exposed_ports(9200)
    )
    with container:
        url = f"http://{container.get_container_host_ip()}:{container.get_exposed_port(9200)}"
        deadline = time.time() + 180
        while time.time() < deadline:
            try:
                if httpx.get(f"{url}/_cluster/health", timeout=2).status_code == 200:
                    break
            except httpx.HTTPError:
                pass
            time.sleep(1)
        else:
            raise RuntimeError("Elasticsearch did not become healthy")
        yield url


@pytest.fixture
async def engine(postgres_url: str) -> AsyncIterator[AsyncEngine]:
    """Migrated engine; tables are emptied for every test."""
    await run_migrations(postgres_url)
    eng = create_engine(postgres_url)
    async with eng.begin() as conn:
        await conn.execute(text("TRUNCATE copilot.documents CASCADE"))
    yield eng
    await eng.dispose()


@pytest.fixture
async def es_client(elasticsearch_url: str) -> AsyncIterator[AsyncElasticsearch]:
    """Elasticsearch client."""
    client = AsyncElasticsearch(elasticsearch_url)
    yield client
    await client.close()


@pytest.fixture
async def es_index(es_client: AsyncElasticsearch) -> EsKeywordIndex:
    """Freshly created test index."""
    index = EsKeywordIndex(es_client, TEST_INDEX)
    if await es_client.indices.exists(index=TEST_INDEX):
        await es_client.indices.delete(index=TEST_INDEX)
    await index.ensure_index()
    return index


@pytest.fixture
async def rag(
    engine: AsyncEngine, es_index: EsKeywordIndex
) -> tuple[IngestService, Retriever, PgStore, EsKeywordIndex]:
    """Real adapters wired with the deterministic hash embedder."""
    embedder = HashEmbedder()
    store = PgStore(session_factory(engine))
    return (
        IngestService(embedder, store, es_index),
        Retriever(embedder, store, es_index),
        store,
        es_index,
    )
