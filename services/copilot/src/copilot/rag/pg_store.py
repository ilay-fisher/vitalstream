"""Postgres/pgvector adapter: document store plus dense (HNSW cosine) search."""

import uuid
from collections.abc import Sequence

from sqlalchemy import delete, func, select
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from copilot.rag.models import ChunkRow, DocumentRow
from copilot.rag.types import Candidate, NewChunk, StoredChunk, StoredDocument


class PgStore:
    """Implements both `DocumentStore` and `VectorIndex` over schema `copilot`."""

    def __init__(self, sessions: "async_sessionmaker[AsyncSession]") -> None:
        self._sessions = sessions

    async def add_document(
        self, title: str, source: str, chunks: Sequence[NewChunk], provenance: str
    ) -> StoredDocument:
        """Insert the document and all chunks in one transaction."""
        document = DocumentRow(id=uuid.uuid4(), title=title, source=source, provenance=provenance)
        rows = [
            ChunkRow(
                id=uuid.uuid4(),
                document_id=document.id,
                ordinal=c.ordinal,
                content=c.content,
                embedding=list(c.embedding),
            )
            for c in chunks
        ]
        async with self._sessions() as session, session.begin():
            session.add(document)
            await session.flush()
            session.add_all(rows)
        return StoredDocument(
            str(document.id),
            title,
            [
                StoredChunk(str(r.id), str(document.id), title, r.ordinal, r.content, provenance)
                for r in rows
            ],
        )

    async def delete_document(self, document_id: str) -> None:
        """Delete a document; chunks go with it via ON DELETE CASCADE."""
        async with self._sessions() as session, session.begin():
            await session.execute(
                delete(DocumentRow).where(DocumentRow.id == uuid.UUID(document_id))
            )

    async def count_documents(self) -> int:
        """Number of documents."""
        async with self._sessions() as session:
            return int(await session.scalar(select(func.count()).select_from(DocumentRow)) or 0)

    async def all_chunks(self) -> list[StoredChunk]:
        """Every chunk with its document title."""
        stmt = (
            select(
                ChunkRow.id,
                ChunkRow.document_id,
                DocumentRow.title,
                ChunkRow.ordinal,
                ChunkRow.content,
                DocumentRow.provenance,
            )
            .join(DocumentRow, DocumentRow.id == ChunkRow.document_id)
            .order_by(DocumentRow.created_at, ChunkRow.ordinal)
        )
        async with self._sessions() as session:
            rows = (await session.execute(stmt)).all()
        return [StoredChunk(str(r[0]), str(r[1]), r[2], r[3], r[4], r[5]) for r in rows]

    async def search(self, embedding: Sequence[float], k: int) -> list[Candidate]:
        """Nearest chunks by cosine distance, served by the HNSW index."""
        stmt = (
            select(
                ChunkRow.id,
                ChunkRow.document_id,
                DocumentRow.title,
                ChunkRow.content,
                DocumentRow.provenance,
            )
            .join(DocumentRow, DocumentRow.id == ChunkRow.document_id)
            .order_by(ChunkRow.embedding.cosine_distance(list(embedding)))
            .limit(k)
        )
        async with self._sessions() as session:
            rows = (await session.execute(stmt)).all()
        return [Candidate(str(r[0]), str(r[1]), r[2], r[3], r[4]) for r in rows]
