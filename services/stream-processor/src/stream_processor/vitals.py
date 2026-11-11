"""Vital-sign domain constants shared by every detector (CONTRACTS section 3.1 / 3.3)."""

from __future__ import annotations

import math
from dataclasses import dataclass
from enum import Enum


class Metric(str, Enum):
    """Vital-sign metric names exactly as they appear on `vitals.raw`."""

    HEART_RATE = "HEART_RATE"
    SPO2 = "SPO2"
    RESP_RATE = "RESP_RATE"
    SYSTOLIC_BP = "SYSTOLIC_BP"
    DIASTOLIC_BP = "DIASTOLIC_BP"
    TEMPERATURE = "TEMPERATURE"


class Severity(str, Enum):
    """Alert severity; `rank` lets callers compare without relying on declaration order."""

    LOW = "LOW"
    MEDIUM = "MEDIUM"
    HIGH = "HIGH"
    CRITICAL = "CRITICAL"

    @property
    def rank(self) -> int:
        """Return 1 (LOW) .. 4 (CRITICAL)."""
        return _SEVERITY_RANK[self]


_SEVERITY_RANK = {Severity.LOW: 1, Severity.MEDIUM: 2, Severity.HIGH: 3, Severity.CRITICAL: 4}


@dataclass(frozen=True)
class MetricSpec:
    """Ranges for one metric.

    `valid_*` reject physiologically impossible readings, `normal_*` drive THRESHOLD alerts and
    `critical_low`/`critical_high` are the strict-inequality critical bands of CONTRACTS 3.3
    (None means the contract defines no critical band on that side).
    """

    unit: str
    loinc: str
    valid_min: float
    valid_max: float
    normal_min: float
    normal_max: float
    critical_low: float | None = None
    critical_high: float | None = None


SPECS: dict[Metric, MetricSpec] = {
    Metric.HEART_RATE: MetricSpec("/min", "8867-4", 20, 250, 51, 90, 40, 130),
    Metric.SPO2: MetricSpec("%", "59408-5", 50, 100, 96, 100, critical_low=90),
    Metric.RESP_RATE: MetricSpec("/min", "9279-1", 4, 60, 12, 20, 8, 25),
    Metric.SYSTOLIC_BP: MetricSpec("mm[Hg]", "8480-6", 50, 260, 111, 219, critical_low=90),
    Metric.DIASTOLIC_BP: MetricSpec("mm[Hg]", "8462-4", 20, 160, 60, 90),
    Metric.TEMPERATURE: MetricSpec("Cel", "8310-5", 30, 43, 36.1, 38.0, 35, 39.1),
}


def is_valid_reading(metric: Metric, value: float) -> bool:
    """Return True when `value` lies inside the metric's physiological valid range.

    NaN is rejected explicitly because every comparison with NaN is False and it would otherwise
    slip through downstream range checks.
    """
    if math.isnan(value):
        return False
    spec = SPECS[metric]
    return spec.valid_min <= value <= spec.valid_max
