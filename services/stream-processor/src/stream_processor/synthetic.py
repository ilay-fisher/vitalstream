"""Seeded synthetic "normal" 1-minute windows used to train the anomaly model."""

from __future__ import annotations

import random

from stream_processor.anomaly import FEATURE_ORDER
from stream_processor.vitals import SPECS, Metric

# (mean, std) of a healthy resting adult's window average; clipped into the normal range so the
# model never learns that an out-of-range pattern is normal.
_NORMAL_PROFILE: dict[Metric, tuple[float, float]] = {
    Metric.HEART_RATE: (72.0, 6.0),
    Metric.SPO2: (97.8, 0.8),
    Metric.RESP_RATE: (16.0, 1.8),
    Metric.SYSTOLIC_BP: (122.0, 8.0),
    Metric.DIASTOLIC_BP: (75.0, 5.0),
    Metric.TEMPERATURE: (36.8, 0.25),
}


def generate_normal_windows(n: int, seed: int) -> list[list[float]]:
    """Return `n` feature vectors (ordered as `FEATURE_ORDER`) drawn from the normal profile.

    A dedicated `random.Random(seed)` instance keeps the output reproducible regardless of other
    users of the global generator.
    """
    rng = random.Random(seed)
    rows: list[list[float]] = []
    for _ in range(n):
        row: list[float] = []
        for metric in FEATURE_ORDER:
            mean, std = _NORMAL_PROFILE[metric]
            spec = SPECS[metric]
            value = rng.gauss(mean, std)
            row.append(min(max(value, spec.normal_min), spec.normal_max))
        rows.append(row)
    return rows
