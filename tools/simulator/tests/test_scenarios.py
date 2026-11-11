"""Scenario behaviour: the physiology each scenario must produce."""

from __future__ import annotations

import statistics

import pytest

from tests.conftest import T0
from vitalstream_sim.catalog import CATALOG, Metric
from vitalstream_sim.generator import VitalsGenerator
from vitalstream_sim.news2 import news2_score
from vitalstream_sim.patients import build_patients
from vitalstream_sim.scenarios import Scenario

PATIENTS = build_patients()


def snapshot(gen: VitalsGenerator, t: float, tick: int) -> dict[Metric, float]:
    return gen.snapshot(scenario_t=t, tick=tick, at=T0)


def score(values: dict[Metric, float]) -> int:
    return news2_score(
        rr=values[Metric.RESP_RATE],
        spo2=values[Metric.SPO2],
        sbp=values[Metric.SYSTOLIC_BP],
        hr=values[Metric.HEART_RATE],
        temp=values[Metric.TEMPERATURE],
    )


def test_stable_stays_in_valid_range_and_low_news2() -> None:
    worst = 0
    for patient in PATIENTS:
        gen = VitalsGenerator(patient, Scenario.STABLE, seed=1)
        for tick in range(300):
            values = snapshot(gen, tick * 2.0, tick)
            worst = max(worst, score(values))
            for metric, value in values.items():
                spec = CATALOG[metric]
                assert spec.valid_min <= value <= spec.valid_max
    assert worst < 5


@pytest.mark.parametrize("scenario", [Scenario.SEPSIS, Scenario.HYPOXIA])
def test_deteriorating_scenarios_reach_news2_7(scenario: Scenario) -> None:
    for patient in PATIENTS:
        gen = VitalsGenerator(patient, scenario, seed=3)
        ramp = gen.ramp_seconds
        scores = [score(snapshot(gen, ramp * f, i)) for i, f in enumerate([0.0, 0.5, 1.0, 1.5])]
        assert scores[0] < 5
        assert scores[-1] >= 7, (patient.id, scores)


def test_sepsis_trajectory_directions() -> None:
    gen = VitalsGenerator(PATIENTS[0], Scenario.SEPSIS, seed=5, noise_scale=0.0)
    start = snapshot(gen, 0.0, 0)
    end = snapshot(gen, gen.ramp_seconds, 1)
    assert end[Metric.HEART_RATE] > start[Metric.HEART_RATE] + 30
    assert end[Metric.RESP_RATE] > start[Metric.RESP_RATE] + 8
    assert end[Metric.TEMPERATURE] > start[Metric.TEMPERATURE] + 1.5
    assert end[Metric.SYSTOLIC_BP] < start[Metric.SYSTOLIC_BP] - 25
    assert end[Metric.SPO2] < start[Metric.SPO2] - 4
    assert score(end) >= 7


def test_sepsis_news2_is_monotonic_without_noise() -> None:
    gen = VitalsGenerator(PATIENTS[3], Scenario.SEPSIS, seed=5, noise_scale=0.0)
    scores = [score(snapshot(gen, gen.ramp_seconds * i / 20, i)) for i in range(21)]
    assert scores == sorted(scores)
    assert scores[-1] >= 7


def test_arrhythmia_produces_zscore_spikes() -> None:
    gen = VitalsGenerator(PATIENTS[0], Scenario.ARRHYTHMIA, seed=9)
    hr = [snapshot(gen, i * 2.0, i)[Metric.HEART_RATE] for i in range(400)]
    mean, sd = statistics.fmean(hr), statistics.pstdev(hr)
    assert max(hr) <= 250
    spikes = [v for v in hr if (v - mean) / sd >= 2.0]
    assert len(spikes) >= 5
    calm = VitalsGenerator(PATIENTS[0], Scenario.STABLE, seed=9)
    calm_hr = [snapshot(calm, i * 2.0, i)[Metric.HEART_RATE] for i in range(400)]
    assert max(hr) - statistics.median(hr) > 2 * (max(calm_hr) - statistics.median(calm_hr))


def test_sensor_fault_emits_out_of_range_values() -> None:
    gen = VitalsGenerator(PATIENTS[0], Scenario.SENSOR_FAULT, seed=11)
    bad = good = 0
    for tick in range(200):
        for metric, value in snapshot(gen, tick * 2.0, tick).items():
            spec = CATALOG[metric]
            if spec.valid_min <= value <= spec.valid_max:
                good += 1
            else:
                bad += 1
    assert bad >= 50
    assert good >= 50


def test_scenario_names() -> None:
    assert Scenario("sepsis") is Scenario.SEPSIS
    assert {s.value for s in Scenario} == {
        "stable",
        "sepsis",
        "hypoxia",
        "arrhythmia",
        "sensor_fault",
    }
