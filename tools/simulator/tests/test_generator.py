from __future__ import annotations

import statistics
from datetime import timedelta

from tests.conftest import T0
from vitalstream_sim.catalog import CATALOG, Metric
from vitalstream_sim.generator import VitalsGenerator
from vitalstream_sim.patients import build_patients
from vitalstream_sim.scenarios import Scenario

PATIENT = build_patients()[0]


def test_deterministic_for_same_seed_and_differs_across_seeds() -> None:
    a = VitalsGenerator(PATIENT, Scenario.STABLE, seed=1).snapshot(scenario_t=10, tick=5, at=T0)
    b = VitalsGenerator(PATIENT, Scenario.STABLE, seed=1).snapshot(scenario_t=10, tick=5, at=T0)
    c = VitalsGenerator(PATIENT, Scenario.STABLE, seed=2).snapshot(scenario_t=10, tick=5, at=T0)
    assert a == b
    assert a != c


def test_snapshot_covers_all_metrics_with_sensible_rounding() -> None:
    values = VitalsGenerator(PATIENT, Scenario.STABLE).snapshot(scenario_t=0, tick=0, at=T0)
    assert set(values) == set(Metric)
    assert values[Metric.HEART_RATE] == round(values[Metric.HEART_RATE])
    assert values[Metric.TEMPERATURE] == round(values[Metric.TEMPERATURE], 1)


def test_noise_present_and_scalable() -> None:
    noisy = VitalsGenerator(PATIENT, Scenario.STABLE, seed=1)
    quiet = VitalsGenerator(PATIENT, Scenario.STABLE, seed=1, noise_scale=0.0)
    hr_noisy = [noisy.snapshot(i * 2.0, i, T0)[Metric.HEART_RATE] for i in range(200)]
    hr_quiet = [quiet.snapshot(i * 2.0, i, T0)[Metric.HEART_RATE] for i in range(200)]
    assert statistics.pstdev(hr_noisy) > 0.5
    assert len(set(hr_quiet)) == 1


def test_circadian_drift_evening_higher_than_early_morning() -> None:
    gen = VitalsGenerator(PATIENT, Scenario.STABLE, seed=1, noise_scale=0.0)
    morning = T0.replace(hour=4, minute=0)
    evening = morning + timedelta(hours=12)
    low = gen.snapshot(0, 0, morning)
    high = gen.snapshot(0, 0, evening)
    assert high[Metric.TEMPERATURE] > low[Metric.TEMPERATURE]
    assert high[Metric.HEART_RATE] >= low[Metric.HEART_RATE]


def test_values_clamped_to_valid_range_outside_fault() -> None:
    gen = VitalsGenerator(PATIENT, Scenario.HYPOXIA, seed=1)
    for tick in range(100):
        for metric, value in gen.snapshot(tick * 100.0, tick, T0).items():
            spec = CATALOG[metric]
            assert spec.valid_min <= value <= spec.valid_max
