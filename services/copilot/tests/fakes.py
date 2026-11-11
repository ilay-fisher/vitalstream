"""In-memory adapters for the RAG ports, used by unit tests (no Docker needed)."""

import math
import re
from collections.abc import Sequence
from uuid import uuid4

from copilot.rag.types import (
    Candidate,
    NewChunk,
    StoredChunk,
    StoredDocument,
)


def _cosine(a: Sequence[float], b: Sequence[float]) -> float:
    dot = sum(x * y for x, y in zip(a, b, strict=True))
    na = math.sqrt(sum(x * x for x in a))
    nb = math.sqrt(sum(y * y for y in b))
    return dot / (na * nb) if na and nb else 0.0


class InMemoryStore:
    """DocumentStore + VectorIndex in one object."""

    def __init__(self) -> None:
        self.docs: dict[str, StoredDocument] = {}
        self.vectors: dict[str, Sequence[float]] = {}

    async def add_document(
        self, title: str, source: str, chunks: Sequence[NewChunk]
    ) -> StoredDocument:
        document_id = str(uuid4())
        stored = [
            StoredChunk(str(uuid4()), document_id, title, c.ordinal, c.content) for c in chunks
        ]
        for stored_chunk, new_chunk in zip(stored, chunks, strict=True):
            self.vectors[stored_chunk.chunk_id] = new_chunk.embedding
        doc = StoredDocument(document_id, title, stored)
        self.docs[document_id] = doc
        return doc

    async def delete_document(self, document_id: str) -> None:
        doc = self.docs.pop(document_id, None)
        for chunk in doc.chunks if doc else []:
            self.vectors.pop(chunk.chunk_id, None)

    async def count_documents(self) -> int:
        return len(self.docs)

    async def all_chunks(self) -> list[StoredChunk]:
        return [c for d in self.docs.values() for c in d.chunks]

    async def search(self, embedding: Sequence[float], k: int) -> list[Candidate]:
        scored = sorted(
            ((_cosine(embedding, self.vectors[c.chunk_id]), c) for c in await self.all_chunks()),
            key=lambda pair: -pair[0],
        )
        return [Candidate(c.chunk_id, c.document_id, c.title, c.content) for _, c in scored[:k]]


class InMemoryKeywordIndex:
    """Naive term-overlap stand-in for BM25."""

    def __init__(self) -> None:
        self.chunks: dict[str, StoredChunk] = {}

    async def index_chunks(self, chunks: Sequence[StoredChunk]) -> None:
        for chunk in chunks:
            self.chunks[chunk.chunk_id] = chunk

    async def delete_document(self, document_id: str) -> None:
        self.chunks = {k: v for k, v in self.chunks.items() if v.document_id != document_id}

    async def count(self) -> int:
        return len(self.chunks)

    async def search(self, query: str, k: int) -> list[Candidate]:
        terms = set(re.findall(r"\w+", query.lower()))
        scored = []
        for chunk in self.chunks.values():
            words = re.findall(r"\w+", chunk.content.lower())
            score = sum(words.count(t) for t in terms)
            if score:
                scored.append((score, chunk))
        scored.sort(key=lambda pair: -pair[0])
        return [Candidate(c.chunk_id, c.document_id, c.title, c.content) for _, c in scored[:k]]


class FailingKeywordIndex(InMemoryKeywordIndex):
    """Simulates Elasticsearch being down."""

    async def search(self, query: str, k: int) -> list[Candidate]:
        raise ConnectionError("elasticsearch unavailable")
