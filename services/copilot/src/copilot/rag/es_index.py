"""Elasticsearch adapter: the BM25 side of hybrid search (index `guidelines`)."""

from collections.abc import Sequence

from elasticsearch import AsyncElasticsearch, NotFoundError
from elasticsearch.helpers import async_bulk

from copilot.rag.types import Candidate, StoredChunk

_MAPPING = {
    "properties": {
        "chunk_id": {"type": "keyword"},
        "document_id": {"type": "keyword"},
        "title": {"type": "text", "analyzer": "english"},
        "content": {"type": "text", "analyzer": "english"},
        "provenance": {"type": "keyword"},
    }
}


class EsKeywordIndex:
    """BM25 over chunk text using the `english` analyzer (stemming, stop words)."""

    def __init__(self, client: AsyncElasticsearch, index: str = "guidelines") -> None:
        self._client = client
        self._index = index

    async def ensure_index(self) -> None:
        """Create the index with an explicit mapping if it does not exist."""
        if not await self._client.indices.exists(index=self._index):
            await self._client.indices.create(index=self._index, mappings=_MAPPING)

    async def index_chunks(self, chunks: Sequence[StoredChunk]) -> None:
        """Bulk-index chunks, waiting for a refresh so they are immediately searchable."""
        actions = (
            {
                "_index": self._index,
                "_id": c.chunk_id,
                "_source": {
                    "chunk_id": c.chunk_id,
                    "document_id": c.document_id,
                    "title": c.title,
                    "content": c.content,
                    "provenance": c.provenance,
                },
            }
            for c in chunks
        )
        await async_bulk(self._client, actions, refresh="wait_for")

    async def delete_document(self, document_id: str) -> None:
        """Remove all chunks of a document."""
        await self._client.delete_by_query(
            index=self._index,
            query={"term": {"document_id": document_id}},
            refresh=True,
            conflicts="proceed",
        )

    async def count(self) -> int:
        """Number of indexed chunks (0 if the index is missing)."""
        try:
            return int((await self._client.count(index=self._index))["count"])
        except NotFoundError:
            return 0

    async def search(self, query: str, k: int) -> list[Candidate]:
        """Top ``k`` BM25 matches; title matches are boosted."""
        response = await self._client.search(
            index=self._index,
            size=k,
            query={"multi_match": {"query": query, "fields": ["title^2", "content"]}},
        )
        return [
            Candidate(
                chunk_id=hit["_source"]["chunk_id"],
                document_id=hit["_source"]["document_id"],
                title=hit["_source"]["title"],
                content=hit["_source"]["content"],
                provenance=hit["_source"].get("provenance", "api-upload"),
            )
            for hit in response["hits"]["hits"]
        ]
