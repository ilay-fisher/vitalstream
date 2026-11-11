"""Pure glue between window/stream data and the individual detectors.

Nothing here touches Spark, Redis or Kafka, so the whole detection policy is unit-testable.
"""

from __future__ import annotations

from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone

from stream_processor.alerts import (
    Alert,
    make_anomaly_alert,
    make_news2_alert,
    make_threshold_alert,
    make_trend_alert,
)
from stream_processor.anomaly import AnomalyModel, feature_vector
from stream_processor.ewma import EwmaConfig, EwmaState, scan_series
from stream_processor.news2 import compute_news2
from stream_processor.vitals import Metric


@dataclass(frozen=True)
class WindowSnapshot:
    """One patient's 1-minute window: per-metric averages and latest values."""

    patient_id: str
    window_start: datetime
    window_end: datetime
    avg: Mapping[Metric, float | None]
    last: Mapping[Metric, float | None]
    correlation_id: str | None


def window_bounds(ts_ms: int, window_seconds: int) -> tuple[datetime, datetime]:
    """Return the tumbling window [start, end) that contains an epoch-millisecond timestamp."""
    seconds = ts_ms // 1000
    start_epoch = seconds - seconds % window_seconds
    start = datetime.fromtimestamp(start_epoch, tz=timezone.utc)
    return start, start + timedelta(seconds=window_seconds)


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


def detect_trend_alerts(
    patient_id: str,
    metric: Metric,
    state: EwmaState | None,
    samples: Sequence[tuple[int, float]],
    cfg: EwmaConfig,
    now: datetime,
    window_seconds: int = 60,
) -> tuple[EwmaState | None, list[Alert]]:
    """Advance the EWMA state over `samples` and raise one TREND_ZSCORE alert per window.

    When several samples in the same window trip the threshold only the one with the largest
    |z| is reported, matching the one-alert-per-(patient, type, metric, window) alertId scheme.
    """
    new_state, hits = scan_series(state, samples, cfg)
    worst_per_window: dict[datetime, tuple[float, float, datetime]] = {}
    for hit in hits:
        start, end = window_bounds(hit.ts, window_seconds)
        current = worst_per_window.get(start)
        if current is None or abs(hit.z) > abs(current[0]):
            worst_per_window[start] = (hit.z, hit.value, end)
    alerts = [
        make_trend_alert(patient_id, metric, value, z, start, end, now)
        for start, (z, value, end) in sorted(worst_per_window.items())
    ]
    return new_state, alerts
