"""Per-patient vital-sign generator: baseline + circadian drift + noise + scenario."""

from __future__ import annotations

import math
import random
from datetime import datetime

from vitalstream_sim.catalog import CATALOG, Metric
from vitalstream_sim.patients import Patient
from vitalstream_sim.scenarios import (
    ARRHYTHMIA_SPIKE_PROBABILITY,
    ARRHYTHMIA_SPIKE_RANGE,
    FAULT_PROBABILITY,
    FAULT_VALUES,
    Scenario,
    ramp_offset,
    ramp_seconds,
)

# Peak-to-mean amplitude of the 24 h rhythm (peaks 16:00 UTC, trough 04:00 UTC).
CIRCADIAN_AMPLITUDE: dict[Metric, float] = {
    Metric.HEART_RATE: 4.0,
    Metric.SPO2: 0.3,
    Metric.RESP_RATE: 0.6,
    Metric.SYSTOLIC_BP: 5.0,
    Metric.DIASTOLIC_BP: 3.0,
    Metric.TEMPERATURE: 0.35,
}

# Standard deviation of beat-to-beat measurement noise.
NOISE_SIGMA: dict[Metric, float] = {
    Metric.HEART_RATE: 2.0,
    Metric.SPO2: 0.4,
    Metric.RESP_RATE: 0.8,
    Metric.SYSTOLIC_BP: 3.0,
    Metric.DIASTOLIC_BP: 2.0,
    Metric.TEMPERATURE: 0.08,
}

CIRCADIAN_PHASE_HOUR = 10.0
WANDER_PERIOD_SECONDS = 180.0


class VitalsGenerator:
    """Deterministic for a given (seed, patient, scenario): every sample is a pure function of
    its tick, so a retried or replayed run produces byte-identical readings."""

    def __init__(
        self, patient: Patient, scenario: Scenario, seed: int = 42, noise_scale: float = 1.0
    ) -> None:
        self.patient = patient
        self.scenario = scenario
        self.seed = seed
        self.noise_scale = noise_scale
        self._phase = random.Random(f"{seed}|{patient.id}|phase").uniform(0, 2 * math.pi)

    @property
    def ramp_seconds(self) -> float:
        """Scenario seconds until the scenario is fully developed."""
        return ramp_seconds(self.scenario)

    def snapshot(self, scenario_t: float, tick: int, at: datetime) -> dict[Metric, float]:
        """All six metrics for one tick.

        ``scenario_t`` is seconds of scenario time (wall time x speed), ``tick`` the sample index
        (noise seed) and ``at`` the observation time (drives the circadian rhythm).
        """
        return {metric: self._sample(metric, scenario_t, tick, at) for metric in Metric}

    def _rng(self, metric: Metric, tick: int, stream: str) -> random.Random:
        return random.Random(f"{self.seed}|{self.patient.id}|{metric}|{tick}|{stream}")

    def _sample(self, metric: Metric, scenario_t: float, tick: int, at: datetime) -> float:
        spec = CATALOG[metric]
        if self.scenario is Scenario.SENSOR_FAULT:
            fault = self._rng(metric, tick, "fault")
            if fault.random() < FAULT_PROBABILITY:
                return fault.choice(FAULT_VALUES[metric])

        hour = at.hour + at.minute / 60.0
        circadian = CIRCADIAN_AMPLITUDE[metric] * math.sin(
            2 * math.pi * (hour - CIRCADIAN_PHASE_HOUR) / 24.0
        )
        sigma = NOISE_SIGMA[metric] * self.noise_scale
        wander = (
            0.5 * sigma * math.sin(2 * math.pi * scenario_t / WANDER_PERIOD_SECONDS + self._phase)
        )
        noise = self._rng(metric, tick, "noise").gauss(0.0, sigma) if sigma else 0.0

        value = self.patient.baselines[metric] + circadian + wander + noise
        value += ramp_offset(self.scenario, metric, scenario_t)
        if self.scenario is Scenario.ARRHYTHMIA and metric is Metric.HEART_RATE:
            value += self._arrhythmia_spike(tick)

        value = min(max(value, spec.valid_min), spec.valid_max)
        return round(value, spec.decimals)

    def _arrhythmia_spike(self, tick: int) -> float:
        rng = self._rng(Metric.HEART_RATE, tick, "spike")
        if rng.random() < ARRHYTHMIA_SPIKE_PROBABILITY:
            return rng.uniform(*ARRHYTHMIA_SPIKE_RANGE)
        return 0.0
