"""Synthetic patients P-0001..P-0020 matching the foundation seed (infra/postgres/init.sql)."""

from __future__ import annotations

import random
from dataclasses import dataclass

from vitalstream_sim.catalog import Metric

MAX_PATIENTS = 20
ICU_PATIENTS = 10

# Per-patient baselines are drawn from these ranges, all inside the normal band of the contract.
BASELINE_RANGES: dict[Metric, tuple[float, float]] = {
    Metric.HEART_RATE: (58.0, 82.0),
    Metric.SPO2: (97.5, 99.0),
    Metric.RESP_RATE: (13.0, 18.0),
    Metric.SYSTOLIC_BP: (112.0, 135.0),
    Metric.DIASTOLIC_BP: (62.0, 84.0),
    Metric.TEMPERATURE: (36.4, 37.2),
}


@dataclass(frozen=True)
class Patient:
    """A simulated patient with a bedside device and a resting physiologic baseline."""

    id: str
    device_id: str
    ward: str
    baselines: dict[Metric, float]


def build_patients(seed: int = 42, count: int = MAX_PATIENTS) -> list[Patient]:
    """Build the first ``count`` seed patients; same seed gives identical baselines."""
    if not 1 <= count <= MAX_PATIENTS:
        raise ValueError(f"patient count must be between 1 and {MAX_PATIENTS}, got {count}")
    patients: list[Patient] = []
    for number in range(1, count + 1):
        rng = random.Random(f"{seed}|baseline|{number}")
        icu = number <= ICU_PATIENTS
        baselines = {
            metric: round(rng.uniform(low, high), 1)
            for metric, (low, high) in BASELINE_RANGES.items()
        }
        patients.append(
            Patient(
                id=f"P-{number:04d}",
                device_id=f"DEV-{'ICU' if icu else 'CARD'}-{number:04d}",
                ward="ICU" if icu else "CARDIOLOGY",
                baselines=baselines,
            )
        )
    return patients
