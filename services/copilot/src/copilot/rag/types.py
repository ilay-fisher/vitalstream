"""Value types and ports of the RAG pipeline (adapters live in pg_store.py / es_index.py)."""

from collections.abc import Sequence
from dataclasses import dataclass
from typing import Protocol


@dataclass(frozen=True, slots=True)
class Candidate:
    """A chunk returned by one retrieval side, before fusion."""

    chunk_id: str
    document_id: str
    title: str
    content: str
    provenance: str = "api-upload"


@dataclass(frozen=True, slots=True)
class SearchHit:
    """A fused retrieval result with provenance of each ranking."""

    chunk_id: str
    document_id: str
    title: str
    content: str
    score: float
    vector_rank: int | None
    bm25_rank: int | None
    provenance: str = "api-upload"


@dataclass(frozen=True, slots=True)
class NewChunk:
    """A chunk ready to be persisted, with its embedding."""

    ordinal: int
    content: str
    embedding: Sequence[float]


@dataclass(frozen=True, slots=True)
class StoredChunk:
    """A persisted chunk (what the keyword index needs)."""

    chunk_id: str
    document_id: str
    title: str
    ordinal: int
    content: str
    provenance: str = "api-upload"


@dataclass(frozen=True, slots=True)
class StoredDocument:
    """A persisted document and its chunks."""

    document_id: str
    title: str
    chunks: list[StoredChunk]


class Embedder(Protocol):
    """Turns text into fixed-size dense vectors (blocking; callers offload to a thread)."""

    @property
    def dimensions(self) -> int:
        """Vector size; must match the `vector(384)` column."""
        ...

    def embed_documents(self, texts: Sequence[str]) -> list[list[float]]:
        """Embed passages for indexing."""
        ...

    def embed_query(self, text: str) -> list[float]:
        """Embed a search query (some models use a different prefix than for passages)."""
        ...


class VectorIndex(Protocol):
    """Dense nearest-neighbour search."""

    async def search(self, embedding: Sequence[float], k: int) -> list[Candidate]:
        """Return the ``k`` nearest chunks by cosine distance, best first."""
        ...


class KeywordIndex(Protocol):
    """Lexical (BM25) search."""

    async def search(self, query: str, k: int) -> list[Candidate]:
        """Return the ``k`` best BM25 matches, best first."""
        ...

    async def index_chunks(self, chunks: Sequence[StoredChunk]) -> None:
        """Add chunks to the index."""
        ...

    async def delete_document(self, document_id: str) -> None:
        """Remove every chunk of a document."""
        ...

    async def count(self) -> int:
        """Number of indexed chunks."""
        ...


class DocumentStore(Protocol):
    """System of record for documents and chunk embeddings."""

    async def add_document(
        self, title: str, source: str, chunks: Sequence[NewChunk], provenance: str
    ) -> StoredDocument:
        """Persist a document (with its provenance) and its chunks atomically."""
        ...

    async def delete_document(self, document_id: str) -> None:
        """Delete a document (chunks cascade)."""
        ...

    async def count_documents(self) -> int:
        """Number of stored documents."""
        ...

    async def all_chunks(self) -> list[StoredChunk]:
        """Every stored chunk, for rebuilding the keyword index."""
        ...
