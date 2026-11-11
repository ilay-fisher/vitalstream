"""Reciprocal Rank Fusion (Cormack et al., 2009).

RRF merges ranked lists without needing comparable scores, which is exactly the situation with
BM25 (unbounded) versus cosine similarity (0..1).  ``score(d) = sum_r 1 / (k + rank_r(d))``.
"""

from collections.abc import Hashable, Sequence


def reciprocal_rank_fusion[T: Hashable](
    rankings: Sequence[Sequence[T]], k: int = 60
) -> list[tuple[T, float]]:
    """Fuse several best-first rankings into one list of ``(item, score)``, best first.

    Ranks are 1-based. An item repeated inside one ranking counts only at its best position.
    Ties keep first-appearance order so results are deterministic.
    """
    if k <= 0:
        raise ValueError("k must be positive")
    scores: dict[T, float] = {}
    for ranking in rankings:
        seen: set[T] = set()
        for position, item in enumerate(ranking, start=1):
            if item in seen:
                continue
            seen.add(item)
            scores[item] = scores.get(item, 0.0) + 1.0 / (k + position)
    return sorted(scores.items(), key=lambda pair: -pair[1])
