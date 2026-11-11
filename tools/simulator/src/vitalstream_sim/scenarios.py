"""Clinical scenarios that shape a patient's baseline over (scenario) time."""

from __future__ import annotations

from dataclasses import dataclass
from enum import StrEnum

from vitalstream_sim.catalog import Metric


class Scenario(StrEnum):
    """Named trajectories selectable from the CLI."""

    STABLE = "stable"
    SEPSIS = "sepsis"
    HYPOXIA = "hypoxia"
    ARRHYTHMIA = "arrhythmia"
    SENSOR_FAULT = "sensor_fault"


@dataclass(frozen=True)
class Ramp:
    """Smooth drift from baseline to ``baseline + target`` over ``seconds`` of scenario time."""

    targets: dict[Metric, float]
    seconds: float


DEFAULT_RAMP_SECONDS = 600.0

RAMPS: dict[Scenario, Ramp] = {
    # Early sepsis: tachycardia, tachypnoea, fever, hypotension, falling saturation.
    Scenario.SEPSIS: Ramp(
        {
            Metric.HEART_RATE: 48.0,
            Metric.RESP_RATE: 14.0,
            Metric.TEMPERATURE: 2.4,
            Metric.SYSTOLIC_BP: -38.0,
            Metric.DIASTOLIC_BP: -20.0,
            Metric.SPO2: -6.0,
        },
        600.0,
    ),
    # Acute hypoxia: saturation collapses with compensatory tachypnoea and tachycardia.
    Scenario.HYPOXIA: Ramp(
        {Metric.SPO2: -13.0, Metric.RESP_RATE: 14.0, Metric.HEART_RATE: 45.0},
        300.0,
    ),
}

# Arrhythmia: an isolated heart-rate burst on this fraction of ticks, in this range (bpm).
ARRHYTHMIA_SPIKE_PROBABILITY = 0.05
ARRHYTHMIA_SPIKE_RANGE = (50.0, 90.0)

# Sensor fault: this fraction of readings is replaced by a value outside the valid range.
FAULT_PROBABILITY = 0.35
FAULT_VALUES: dict[Metric, tuple[float, ...]] = {
    Metric.HEART_RATE: (0.0, 300.0, 999.0),
    Metric.SPO2: (0.0, 20.0, 140.0),
    Metric.RESP_RATE: (0.0, 90.0),
    Metric.SYSTOLIC_BP: (0.0, 400.0),
    Metric.DIASTOLIC_BP: (0.0, 300.0),
    Metric.TEMPERATURE: (0.0, 5.0, 60.0),
}


def ramp_seconds(scenario: Scenario) -> float:
    """Scenario time needed for the full deterioration."""
    ramp = RAMPS.get(scenario)
    return ramp.seconds if ramp else DEFAULT_RAMP_SECONDS


def ramp_offset(scenario: Scenario, metric: Metric, scenario_t: float) -> float:
    """Additive deviation from baseline at ``scenario_t`` seconds (smoothstep, monotonic)."""
    ramp = RAMPS.get(scenario)
    if ramp is None or metric not in ramp.targets:
        return 0.0
    progress = min(max(scenario_t / ramp.seconds, 0.0), 1.0)
    return ramp.targets[metric] * progress * progress * (3.0 - 2.0 * progress)
