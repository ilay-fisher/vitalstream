"""Embedding backends behind the :class:`~copilot.rag.types.Embedder` protocol."""

import hashlib
import itertools
import math
import re
from collections.abc import Sequence

from copilot.config import Settings
from copilot.rag.types import Embedder

EMBEDDING_DIMENSIONS = 384
_WORD = re.compile(r"[a-z0-9]+(?:\.[0-9]+)?")


class HashEmbedder:
    """Deterministic feature-hashing embedder: no model download, used by tests and offline runs.

    Unigrams and bigrams are hashed into a signed 384-d bag-of-words vector and L2-normalised,
    so lexically related texts have higher cosine similarity.  It is *not* semantic.
    """

    dimensions = EMBEDDING_DIMENSIONS

    def embed_documents(self, texts: Sequence[str]) -> list[list[float]]:
        """Embed passages."""
        return [self._embed(text) for text in texts]

    def embed_query(self, text: str) -> list[float]:
        """Embed a query (same space as passages)."""
        return self._embed(text)

    def _embed(self, text: str) -> list[float]:
        words = _WORD.findall(text.lower()) or ["<empty>"]
        grams = [*words, *(f"{a}_{b}" for a, b in itertools.pairwise(words))]
        vector = [0.0] * self.dimensions
        for gram in grams:
            digest = hashlib.blake2b(gram.encode(), digest_size=8).digest()
            slot = int.from_bytes(digest[:4], "big") % self.dimensions
            vector[slot] += 1.0 if digest[4] & 1 else -1.0
        norm = math.sqrt(sum(x * x for x in vector)) or 1.0
        return [x / norm for x in vector]


class FastEmbedder:
    """BAAI/bge-small-en-v1.5 (384-d) through fastembed's ONNX runtime; loaded lazily."""

    dimensions = EMBEDDING_DIMENSIONS

    def __init__(self, model_name: str, cache_dir: str | None = None) -> None:
        self._model_name = model_name
        self._cache_dir = cache_dir
        self._model: object | None = None

    def _load(self) -> object:
        if self._model is None:
            from fastembed import TextEmbedding

            self._model = TextEmbedding(model_name=self._model_name, cache_dir=self._cache_dir)
        return self._model

    def embed_documents(self, texts: Sequence[str]) -> list[list[float]]:
        """Embed passages for indexing."""
        model = self._load()
        return [[float(x) for x in v] for v in model.embed(list(texts))]  # type: ignore[attr-defined]

    def embed_query(self, text: str) -> list[float]:
        """Embed a query using the model's query instruction."""
        model = self._load()
        return [float(x) for x in next(iter(model.query_embed(text)))]  # type: ignore[attr-defined]


def build_embedder(settings: Settings) -> Embedder:
    """Select the embedder from configuration (`EMBEDDER=fastembed|hash`)."""
    if settings.embedder == "hash":
        return HashEmbedder()
    return FastEmbedder(settings.embedding_model, settings.fastembed_cache_path)
