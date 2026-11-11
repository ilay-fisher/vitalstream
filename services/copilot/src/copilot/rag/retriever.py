"""Hybrid retrieval: dense vectors + BM25, merged with Reciprocal Rank Fusion."""

import asyncio
import time

import structlog

from copilot.metrics import RETRIEVAL_LATENCY
from copilot.rag.fusion import reciprocal_rank_fusion
from copilot.rag.types import Candidate, Embedder, KeywordIndex, SearchHit, VectorIndex

log = structlog.get_logger()


class Retriever:
    """Searches both indexes concurrently and fuses the rankings.

    If one side is unavailable the other still answers (logged at WARN) so a clinician is not
    left without guidance because Elasticsearch restarted; both failing is an error.
    """

    def __init__(
        self,
        embedder: Embedder,
        vector: VectorIndex,
        keyword: KeywordIndex,
        *,
        rrf_k: int = 60,
        candidates: int = 20,
    ) -> None:
        self._embedder = embedder
        self._vector = vector
        self._keyword = keyword
        self._rrf_k = rrf_k
        self._candidates = candidates

    async def search(self, query: str, k: int = 5) -> list[SearchHit]:
        """Return the top ``k`` fused hits for ``query``."""
        if not query.strip():
            return []
        started = time.perf_counter()
        try:
            return await self._search(query, k)
        finally:
            RETRIEVAL_LATENCY.observe(time.perf_counter() - started)

    async def _search(self, query: str, k: int) -> list[SearchHit]:
        embedding = await asyncio.to_thread(self._embedder.embed_query, query)
        dense, lexical = await asyncio.gather(
            self._vector.search(embedding, self._candidates),
            self._keyword.search(query, self._candidates),
            return_exceptions=True,
        )
        if isinstance(dense, BaseException) and isinstance(lexical, BaseException):
            raise dense
        dense_list = self._usable("vector", dense)
        lexical_list = self._usable("bm25", lexical)

        by_id: dict[str, Candidate] = {c.chunk_id: c for c in [*lexical_list, *dense_list]}
        vector_rank = {c.chunk_id: i for i, c in enumerate(dense_list, start=1)}
        bm25_rank = {c.chunk_id: i for i, c in enumerate(lexical_list, start=1)}
        fused = reciprocal_rank_fusion(
            [[c.chunk_id for c in dense_list], [c.chunk_id for c in lexical_list]], k=self._rrf_k
        )
        return [
            SearchHit(
                chunk_id=chunk_id,
                document_id=by_id[chunk_id].document_id,
                title=by_id[chunk_id].title,
                content=by_id[chunk_id].content,
                score=score,
                vector_rank=vector_rank.get(chunk_id),
                bm25_rank=bm25_rank.get(chunk_id),
            )
            for chunk_id, score in fused[:k]
        ]

    @staticmethod
    def _usable(side: str, result: list[Candidate] | BaseException) -> list[Candidate]:
        if isinstance(result, BaseException):
            log.warning("retrieval_side_failed", side=side, error=str(result))
            return []
        return result
