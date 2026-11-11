"""Metric catalog: units, LOINC codes and ranges exactly as in docs/CONTRACTS.md section 3.1."""

from __future__ import annotations

from dataclasses import dataclass
from enum import StrEnum


class Metric(StrEnum):
    """Vital-sign metrics accepted by the ingest gateway."""

    HEART_RATE = "HEART_RATE"
    SPO2 = "SPO2"
    RESP_RATE = "RESP_RATE"
    SYSTOLIC_BP = "SYSTOLIC_BP"
    DIASTOLIC_BP = "DIASTOLIC_BP"
    TEMPERATURE = "TEMPERATURE"


@dataclass(frozen=True)
class MetricSpec:
    """Static facts about one metric; the gateway rejects readings that disagree with them."""

    metric: Metric
    unit: str
    loinc: str
    display: str
    unit_display: str
    valid_min: float
    valid_max: float
    normal_min: float
    normal_max: float
    decimals: int


CATALOG: dict[Metric, MetricSpec] = {
    Metric.HEART_RATE: MetricSpec(
        Metric.HEART_RATE, "/min", "8867-4", "Heart rate", "beats/minute", 20, 250, 51, 90, 0
    ),
    Metric.SPO2: MetricSpec(
        Metric.SPO2,
        "%",
        "59408-5",
        "Oxygen saturation in Arterial blood by Pulse oximetry",
        "%",
        50,
        100,
        96,
        100,
        0,
    ),
    Metric.RESP_RATE: MetricSpec(
        Metric.RESP_RATE, "/min", "9279-1", "Respiratory rate", "breaths/minute", 4, 60, 12, 20, 0
    ),
    Metric.SYSTOLIC_BP: MetricSpec(
        Metric.SYSTOLIC_BP,
        "mm[Hg]",
        "8480-6",
        "Systolic blood pressure",
        "mmHg",
        50,
        260,
        111,
        219,
        0,
    ),
    Metric.DIASTOLIC_BP: MetricSpec(
        Metric.DIASTOLIC_BP,
        "mm[Hg]",
        "8462-4",
        "Diastolic blood pressure",
        "mmHg",
        20,
        160,
        60,
        90,
        0,
    ),
    Metric.TEMPERATURE: MetricSpec(
        Metric.TEMPERATURE, "Cel", "8310-5", "Body temperature", "degC", 30, 43, 36.1, 38.0, 1
    ),
}
