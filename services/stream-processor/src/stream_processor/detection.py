"""Pure glue between window/stream data and the individual detectors.

Nothing here touches Spark, Redis or Kafka, so the whole detection policy is unit-testable.
"""

from __future__ import annotations

from collections.abc import Mapping
from dataclasses import dataclass
from datetime import datetime

from stream_processor.alerts import (
    Alert,
    make_anomaly_alert,
    make_news2_alert,
    make_threshold_alert,
    make_trend_alert,
)
from stream_processor.anomaly import AnomalyModel, feature_vector
from stream_processor.ewma import EwmaConfig, EwmaState, score_window
from stream_processor.news2 import compute_news2
from stream_processor.vitals import MIN_TREND_DELTA, Metric


@dataclass(frozen=True)
class WindowSnapshot:
    """One patient's 1-minute window: per-metric averages and latest values."""

    patient_id: str
    window_start: datetime
    window_end: datetime
    avg: Mapping[Metric, float | None]
    last: Mapping[Metric, float | None]
    correlation_id: str | None


def detect_window_alerts(
    snapshot: WindowSnapshot, model: AnomalyModel | None, now: datetime
) -> list[Alert]:
    """THRESHOLD (per metric), NEWS2 and ML_ANOMALY alerts for one patient window."""
    alerts: list[Alert] = []
    start, end = snapshot.window_start, snapshot.window_end

    for metric in Metric:
        average = snapshot.avg.get(metric)
        if average is None:
            continue
        threshold_alert = make_threshold_alert(
            snapshot.patient_id, metric, average, start, end, now
        )
        if threshold_alert:
            alerts.append(threshold_alert)

    news2_inputs = {m: snapshot.last.get(m) for m in _NEWS2_METRICS}
    if all(v is not None for v in news2_inputs.values()):
        result = compute_news2(
            rr=_required(news2_inputs[Metric.RESP_RATE]),
            spo2=_required(news2_inputs[Metric.SPO2]),
            sbp=_required(news2_inputs[Metric.SYSTOLIC_BP]),
            hr=_required(news2_inputs[Metric.HEART_RATE]),
            temp=_required(news2_inputs[Metric.TEMPERATURE]),
        )
        news2_alert = make_news2_alert(snapshot.patient_id, result, start, end, now)
        if news2_alert:
            alerts.append(news2_alert)

    features = feature_vector(snapshot.avg)
    if model is not None and features is not None:
        score = model.score(features)
        if score > model.threshold:
            alerts.append(
                make_anomaly_alert(snapshot.patient_id, score, model.threshold, start, end, now)
            )
    return alerts


_NEWS2_METRICS = (
    Metric.RESP_RATE,
    Metric.SPO2,
    Metric.SYSTOLIC_BP,
    Metric.HEART_RATE,
    Metric.TEMPERATURE,
)


def _required(value: float | None) -> float:
    if value is None:
        raise ValueError("value unexpectedly missing")
    return value


def detect_trend_alert(
    patient_id: str,
    metric: Metric,
    state: EwmaState | None,
    window_start: datetime,
    window_end: datetime,
    window_mean: float,
    cfg: EwmaConfig,
    now: datetime,
) -> tuple[EwmaState, Alert | None]:
    """Score one closed window mean for TREND_ZSCORE and return the new state and any alert.

    Windows must be fed in order; a window already applied (replay) leaves the state untouched and
    raises nothing, which keeps the Redis state and the alert stream idempotent.
    """
    window_ts = int(window_start.timestamp() * 1000)
    new_state, hit = score_window(state, window_mean, window_ts, MIN_TREND_DELTA[metric], cfg)
    if hit is None:
        return new_state, None
    return new_state, make_trend_alert(patient_id, metric, hit, window_start, window_end, now)
