"""Behavioural tests of TREND_ZSCORE v2 on synthetic 1 Hz series (CONTRACTS 3.3)."""

import random
from collections.abc import Callable
from datetime import datetime, timedelta, timezone

import pytest

from stream_processor.alerts import Alert, AlertType
from stream_processor.detection import detect_trend_alert
from stream_processor.ewma import EwmaConfig, EwmaState
from stream_processor.vitals import Metric, Severity

CFG = EwmaConfig()
START = datetime(2026, 11, 11, 20, 0, 0, tzinfo=timezone.utc)
NOW = datetime(2026, 11, 11, 22, 0, 0, tzinfo=timezone.utc)
SEEDS = range(12)


def window_means(
    true_value: Callable[[int], float], sd: float, minutes: int, seed: int
) -> list[float]:
    """Mean of 60 noisy 1 Hz samples per minute around `true_value(minute)`."""
    rng = random.Random(seed)
    return [
        sum(rng.gauss(true_value(minute), sd) for _ in range(60)) / 60 for minute in range(minutes)
    ]


def drive(metric: Metric, means: list[float]) -> list[tuple[int, Alert]]:
    """Feed consecutive closed windows to the detector; return (minute, alert) pairs."""
    state: EwmaState | None = None
    alerts: list[tuple[int, Alert]] = []
    for minute, mean in enumerate(means):
        start = START + timedelta(minutes=minute)
        state, alert = detect_trend_alert(
            "P-0001", metric, state, start, start + timedelta(minutes=1), mean, CFG, NOW
        )
        if alert is not None:
            alerts.append((minute, alert))
    return alerts


@pytest.mark.parametrize(
    ("metric", "base", "sd"),
    [
        (Metric.HEART_RATE, 75.0, 3.0),
        (Metric.RESP_RATE, 16.0, 1.5),
        (Metric.SPO2, 97.5, 0.6),
        (Metric.SYSTOLIC_BP, 120.0, 5.0),
        (Metric.DIASTOLIC_BP, 75.0, 3.0),
        (Metric.TEMPERATURE, 36.8, 0.1),
    ],
)
def test_stable_noisy_series_raises_no_trend_alerts(metric: Metric, base: float, sd: float) -> None:
    for seed in SEEDS:
        means = window_means(lambda _m, b=base: b, sd, minutes=30, seed=seed)
        assert drive(metric, means) == []


def test_stable_series_with_very_noisy_patient_stays_silent() -> None:
    for seed in SEEDS:
        means = window_means(lambda _m: 75.0, 12.0, minutes=60, seed=seed)
        assert drive(Metric.HEART_RATE, means) == []


def test_heart_rate_ramp_alerts_after_persistence_and_rises() -> None:
    onset, slope = 10, 3.0
    for seed in SEEDS:
        means = window_means(lambda m: 75.0 + max(0, m - onset) * slope, 3.0, 40, seed)
        alerts = drive(Metric.HEART_RATE, means)
        assert alerts, f"seed {seed}: ramp not detected"
        first_minute, first = alerts[0]
        assert first_minute >= onset + 3  # three breaches are needed
        assert first.type is AlertType.TREND_ZSCORE
        assert first.severity is Severity.MEDIUM
        assert first.metric is Metric.HEART_RATE
        assert first.message.startswith("HEART_RATE rising: ")
        assert first.score is not None and first.score >= 3.0
        assert first.value == pytest.approx(means[first_minute])
        assert first.window_start == START + timedelta(minutes=first_minute)


def test_step_change_is_detected_while_the_baseline_adapts() -> None:
    """A +20 bpm step must alert even though its own windows are far outside the old baseline."""
    for seed in SEEDS:
        means = window_means(lambda m: 75.0 if m < 15 else 95.0, 3.0, 40, seed)
        alerts = drive(Metric.HEART_RATE, means)
        assert alerts and 15 + 2 <= alerts[0][0] <= 15 + 4
        assert len(alerts) <= 3  # the baseline catches up, the alarm does not run forever


def test_spo2_fall_alerts_as_falling() -> None:
    for seed in SEEDS:
        means = window_means(lambda m: 97.5 - 4.0 * min(max(0, m - 10), 6), 0.6, 30, seed)
        alerts = drive(Metric.SPO2, means)
        assert alerts, f"seed {seed}: drop not detected"
        assert alerts[0][1].message.startswith("SPO2 falling: ")
        assert alerts[0][1].score is not None and alerts[0][1].score <= -3.0


def test_single_spike_minute_raises_nothing() -> None:
    for seed in SEEDS:
        means = window_means(lambda m: 110.0 if m == 20 else 75.0, 3.0, 40, seed)
        assert drive(Metric.HEART_RATE, means) == []


def test_two_spike_minutes_raise_nothing() -> None:
    for seed in SEEDS:
        means = window_means(lambda m: 112.0 if m in (20, 22) else 75.0, 3.0, 40, seed)
        assert drive(Metric.HEART_RATE, means) == []


def test_shift_below_clinical_delta_raises_nothing() -> None:
    """+8 bpm is statistically obvious against a quiet baseline but clinically irrelevant."""
    for seed in SEEDS:
        means = window_means(lambda m: 75.0 if m < 15 else 83.0, 1.0, 40, seed)
        assert drive(Metric.HEART_RATE, means) == []


def test_slow_drift_below_clinical_delta_raises_nothing() -> None:
    for seed in SEEDS:
        means = window_means(lambda m: 75.0 + 0.2 * m, 1.0, 40, seed)
        assert drive(Metric.HEART_RATE, means) == []


def test_no_alert_before_warmup_completes() -> None:
    means = [75.0, 75.0, 75.0, 120.0, 140.0, 160.0]
    assert drive(Metric.HEART_RATE, means) == []


def test_replayed_window_is_not_folded_twice() -> None:
    means = window_means(lambda _m: 75.0, 3.0, 12, seed=1)
    state: EwmaState | None = None
    for minute, mean in enumerate(means):
        start = START + timedelta(minutes=minute)
        state, _ = detect_trend_alert(
            "P-0001", Metric.HEART_RATE, state, start, start + timedelta(minutes=1), mean, CFG, NOW
        )
    assert state is not None
    last_start = START + timedelta(minutes=len(means) - 1)
    replayed, alert = detect_trend_alert(
        "P-0001",
        Metric.HEART_RATE,
        state,
        last_start,
        last_start + timedelta(minutes=1),
        150.0,
        CFG,
        NOW,
    )
    assert replayed == state
    assert alert is None
    assert state.last_ts == int(last_start.timestamp() * 1000)


def test_replaying_the_alerting_window_does_not_alert_twice() -> None:
    state = EwmaState(mean=75.0, var=1.0, n=30, last_ts=0, hist="++")
    start = START
    end = START + timedelta(minutes=1)
    state_after, first = detect_trend_alert(
        "P-0001", Metric.HEART_RATE, state, start, end, 95.0, CFG, NOW
    )
    assert first is not None
    again_state, again = detect_trend_alert(
        "P-0001", Metric.HEART_RATE, state_after, start, end, 95.0, CFG, NOW
    )
    assert again is None and again_state == state_after


def test_alert_is_keyed_on_the_window_and_carries_its_bounds() -> None:
    state = EwmaState(mean=75.0, var=1.0, n=30, last_ts=0, hist="++")
    end = START + timedelta(minutes=1)
    _, alert = detect_trend_alert("P-0001", Metric.HEART_RATE, state, START, end, 95.0, CFG, NOW)
    assert alert is not None
    assert (alert.window_start, alert.window_end, alert.detected_at) == (START, end, NOW)
    assert alert.message == "HEART_RATE rising: 95.0 vs trend 75.0 (+20.0, z=13.3, 3 of 5 min)"
