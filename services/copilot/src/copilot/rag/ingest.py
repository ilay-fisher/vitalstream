"""Document ingestion: chunk → embed → persist to Postgres → index in Elasticsearch."""

import asyncio
import re
from dataclasses import dataclass
from pathlib import Path

import structlog

from copilot.rag.chunker import chunk_markdown
from copilot.rag.types import DocumentStore, Embedder, KeywordIndex, NewChunk

log = structlog.get_logger()
_H1 = re.compile(r"^#\s+(.+?)\s*$", re.MULTILINE)


@dataclass(frozen=True, slots=True)
class IngestedDocument:
    """Result of ingesting one document."""

    document_id: str
    title: str
    chunks: int


def extract_title(markdown: str, fallback: str) -> str:
    """Title = first level-1 heading, else the fallback (usually the file name)."""
    match = _H1.search(markdown)
    return match.group(1) if match else fallback


class IngestService:
    """Writes documents to both retrieval sides, keeping them consistent."""

    def __init__(
        self,
        embedder: Embedder,
        store: DocumentStore,
        keyword: KeywordIndex,
        *,
        chunk_tokens: int = 400,
        overlap_tokens: int = 60,
    ) -> None:
        self._embedder = embedder
        self._store = store
        self._keyword = keyword
        self._chunk_tokens = chunk_tokens
        self._overlap_tokens = overlap_tokens

    async def ingest(
        self, *, title: str | None, content: str, source: str, provenance: str = "api-upload"
    ) -> IngestedDocument:
        """Ingest one markdown document.

        The Postgres write is the system of record; if indexing in Elasticsearch fails the
        document is removed again so the two sides never silently diverge.
        """
        resolved_title = title or extract_title(content, fallback=source)
        chunks = chunk_markdown(
            content,
            title=resolved_title,
            max_tokens=self._chunk_tokens,
            overlap_tokens=self._overlap_tokens,
        )
        if not chunks:
            raise ValueError("document has no content to index")
        embeddings = await asyncio.to_thread(
            self._embedder.embed_documents, [c.content for c in chunks]
        )
        stored = await self._store.add_document(
            resolved_title,
            source,
            [NewChunk(c.ordinal, c.content, e) for c, e in zip(chunks, embeddings, strict=True)],
            provenance,
        )
        try:
            await self._keyword.index_chunks(stored.chunks)
        except Exception:
            await self._store.delete_document(stored.document_id)
            raise
        log.info("document_ingested", title=resolved_title, chunks=len(stored.chunks))
        return IngestedDocument(stored.document_id, stored.title, len(stored.chunks))

    async def ingest_corpus_if_empty(self, corpus_dir: Path) -> int:
        """Load every `*.md` in ``corpus_dir`` unless the store already has documents."""
        if await self._store.count_documents() > 0:
            return 0
        count = 0
        files = await asyncio.to_thread(lambda: sorted(corpus_dir.glob("*.md")))
        for path in files:
            content = await asyncio.to_thread(path.read_text, "utf-8")
            await self.ingest(
                title=None,
                content=content,
                source=f"corpus/{path.name}",
                provenance="curated",
            )
            count += 1
        log.info("corpus_ingested", documents=count, directory=str(corpus_dir))
        return count

    async def reindex_keywords_if_empty(self) -> int:
        """Rebuild the Elasticsearch side from Postgres when it was wiped (returns chunks added)."""
        if await self._keyword.count() > 0:
            return 0
        chunks = await self._store.all_chunks()
        if chunks:
            await self._keyword.index_chunks(chunks)
            log.info("keyword_index_rebuilt", chunks=len(chunks))
        return len(chunks)
