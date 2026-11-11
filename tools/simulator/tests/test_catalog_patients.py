from __future__ import annotations

import re

import pytest

from vitalstream_sim.catalog import CATALOG, Metric
from vitalstream_sim.patients import build_patients

CONTRACT_TABLE = {
    "HEART_RATE": ("/min", "8867-4", 20, 250),
    "SPO2": ("%", "59408-5", 50, 100),
    "RESP_RATE": ("/min", "9279-1", 4, 60),
    "SYSTOLIC_BP": ("mm[Hg]", "8480-6", 50, 260),
    "DIASTOLIC_BP": ("mm[Hg]", "8462-4", 20, 160),
    "TEMPERATURE": ("Cel", "8310-5", 30, 43),
}


@pytest.mark.parametrize("name", list(CONTRACT_TABLE))
def test_catalog_matches_contract(name: str) -> None:
    unit, loinc, lo, hi = CONTRACT_TABLE[name]
    spec = CATALOG[Metric(name)]
    assert (spec.unit, spec.loinc, spec.valid_min, spec.valid_max) == (unit, loinc, lo, hi)
    assert spec.valid_min < spec.normal_min < spec.normal_max <= spec.valid_max


def test_twenty_patients_match_seed_ids() -> None:
    patients = build_patients()
    assert [p.id for p in patients] == [f"P-{i:04d}" for i in range(1, 21)]
    assert len({p.device_id for p in patients}) == 20
    for p in patients:
        assert re.fullmatch(r"DEV-[A-Z]+-\d{4,}", p.device_id)
    assert patients[0].device_id == "DEV-ICU-0001"


def test_baselines_are_physiologic_and_deterministic() -> None:
    first = build_patients(seed=7)
    again = build_patients(seed=7)
    assert first == again
    assert build_patients(seed=8) != first
    for p in first:
        for metric, value in p.baselines.items():
            spec = CATALOG[metric]
            assert spec.normal_min <= value <= spec.normal_max


def test_patient_count_limits() -> None:
    assert len(build_patients(count=5)) == 5
    with pytest.raises(ValueError, match="between 1 and 20"):
        build_patients(count=21)
    with pytest.raises(ValueError, match="between 1 and 20"):
        build_patients(count=0)
