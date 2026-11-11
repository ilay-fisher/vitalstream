"""ML_ANOMALY scoring: standardised window means, distance to the nearest KMeans centroid.

Training happens offline with Spark ML (`training.py`); scoring is plain Python on the exported
centroids so the streaming job needs no JVM round trip per window and the maths is unit-testable.
"""

from __future__ import annotations

import json
import math
from collections.abc import Mapping, Sequence
from dataclasses import asdict, dataclass
from pathlib import Path

from stream_processor.vitals import Metric

FEATURE_ORDER: tuple[Metric, ...] = (
    Metric.HEART_RATE,
    Metric.SPO2,
    Metric.RESP_RATE,
    Metric.SYSTOLIC_BP,
    Metric.DIASTOLIC_BP,
    Metric.TEMPERATURE,
)


def feature_vector(means: Mapping[Metric, float | None]) -> list[float] | None:
    """Order per-window means into the model's feature vector; None if any metric is missing."""
    values: list[float] = []
    for metric in FEATURE_ORDER:
        value = means.get(metric)
        if value is None:
            return None
        values.append(value)
    return values


@dataclass(frozen=True)
class Standardizer:
    """Per-feature z-scaling parameters learned from the training windows."""

    means: list[float]
    stds: list[float]

    def transform(self, row: Sequence[float]) -> list[float]:
        """Return (x - mean) / std per feature."""
        return [(x - m) / s for x, m, s in zip(row, self.means, self.stds, strict=True)]


def fit_standardizer(rows: Sequence[Sequence[float]]) -> Standardizer:
    """Fit population mean/std per column; zero-variance columns get std 1 to avoid /0."""
    if not rows:
        raise ValueError("need at least one row to fit a standardizer")
    n = len(rows)
    width = len(rows[0])
    means = [sum(row[j] for row in rows) / n for j in range(width)]
    stds: list[float] = []
    for j in range(width):
        var = sum((row[j] - means[j]) ** 2 for row in rows) / n
        std = math.sqrt(var)
        stds.append(std if std > 0 else 1.0)
    return Standardizer(means=means, stds=stds)


def distance_to_nearest(point: Sequence[float], centroids: Sequence[Sequence[float]]) -> float:
    """Euclidean distance from `point` to its closest centroid."""
    if not centroids:
        raise ValueError("at least one centroid is required")
    return min(math.dist(point, c) for c in centroids)


def percentile(values: Sequence[float], q: float) -> float:
    """q-th percentile (0-100) with linear interpolation, identical to numpy's default."""
    if not values:
        raise ValueError("percentile of empty sequence")
    if not 0 <= q <= 100:
        raise ValueError("q must be between 0 and 100")
    ordered = sorted(values)
    rank = (len(ordered) - 1) * q / 100.0
    lower = math.floor(rank)
    upper = math.ceil(rank)
    if lower == upper:
        return ordered[lower]
    return ordered[lower] + (ordered[upper] - ordered[lower]) * (rank - lower)


@dataclass(frozen=True)
class AnomalyModel:
    """Everything needed to score a window: scaler, centroids and the alert threshold."""

    standardizer: Standardizer
    centroids: list[list[float]]
    threshold: float
    k: int
    silhouette: float
    training_rows: int

    def score(self, features: Sequence[float]) -> float:
        """Distance to the nearest centroid in standardised space."""
        if len(features) != len(self.standardizer.means):
            raise ValueError(
                f"feature dimension {len(features)} != model dimension "
                f"{len(self.standardizer.means)}"
            )
        return distance_to_nearest(self.standardizer.transform(features), self.centroids)

    def is_anomalous(self, features: Sequence[float]) -> bool:
        """Return True when the score is strictly above the 99th-percentile training distance."""
        return self.score(features) > self.threshold

    def to_json(self) -> str:
        """Serialise to the JSON file the stream job loads."""
        return json.dumps(asdict(self), indent=2, sort_keys=True)

    @classmethod
    def from_json(cls, text: str) -> AnomalyModel:
        """Inverse of `to_json`."""
        raw = json.loads(text)
        return cls(
            standardizer=Standardizer(**raw["standardizer"]),
            centroids=[list(c) for c in raw["centroids"]],
            threshold=float(raw["threshold"]),
            k=int(raw["k"]),
            silhouette=float(raw["silhouette"]),
            training_rows=int(raw["training_rows"]),
        )

    def save(self, path: Path) -> None:
        """Write the model, creating parent directories."""
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(self.to_json(), encoding="utf-8")

    @classmethod
    def load(cls, path: Path) -> AnomalyModel | None:
        """Load a saved model; None when the file does not exist."""
        if not path.exists():
            return None
        return cls.from_json(path.read_text(encoding="utf-8"))
