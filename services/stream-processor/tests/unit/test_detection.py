from datetime import datetime, timezone

from stream_processor.alerts import AlertType
from stream_processor.anomaly import AnomalyModel, Standardizer
from stream_processor.detection import (
    WindowSnapshot,
    detect_window_alerts,
)
from stream_processor.vitals import Metric, Severity

WS = datetime(2026, 11, 11, 20, 15, 0, tzinfo=timezone.utc)
WE = datetime(2026, 11, 11, 20, 16, 0, tzinfo=timezone.utc)
NOW = datetime(2026, 11, 11, 20, 16, 2, tzinfo=timezone.utc)

NORMAL = {
    Metric.HEART_RATE: 72.0,
    Metric.SPO2: 98.0,
    Metric.RESP_RATE: 16.0,
    Metric.SYSTOLIC_BP: 120.0,
    Metric.DIASTOLIC_BP: 75.0,
    Metric.TEMPERATURE: 36.8,
}


def snapshot(avg: dict[Metric, float | None], last: dict[Metric, float | None] | None = None):
    return WindowSnapshot(
        patient_id="P-0001",
        window_start=WS,
        window_end=WE,
        avg=avg,
        last=last if last is not None else avg,
        correlation_id="corr-1",
    )


def _model(threshold: float = 3.0) -> AnomalyModel:
    return AnomalyModel(
        standardizer=Standardizer(means=list(NORMAL.values()), stds=[1.0] * 6),
        centroids=[[0.0] * 6],
        threshold=threshold,
        k=1,
        silhouette=0.0,
        training_rows=1,
    )


def test_healthy_window_raises_nothing() -> None:
    assert detect_window_alerts(snapshot(dict(NORMAL)), _model(), NOW) == []


def test_threshold_alerts_per_metric_outside_normal() -> None:
    avg = {**NORMAL, Metric.HEART_RATE: 105.0, Metric.SPO2: 85.0}
    alerts = detect_window_alerts(snapshot(avg), None, NOW)
    by_metric = {a.metric: a for a in alerts if a.type is AlertType.THRESHOLD}
    assert by_metric[Metric.HEART_RATE].severity is Severity.MEDIUM
    assert by_metric[Metric.SPO2].severity is Severity.CRITICAL


def test_news2_uses_latest_values_not_averages() -> None:
    avg = dict(NORMAL)
    last = {**NORMAL, Metric.RESP_RATE: 26.0, Metric.SPO2: 90.0, Metric.HEART_RATE: 135.0}
    alerts = detect_window_alerts(snapshot(avg, last), None, NOW)
    news = [a for a in alerts if a.type is AlertType.NEWS2]
    assert len(news) == 1
    assert news[0].severity is Severity.CRITICAL
    assert news[0].score == 9.0
    assert not [a for a in alerts if a.type is AlertType.THRESHOLD]


def test_news2_skipped_when_a_parameter_is_missing() -> None:
    last: dict[Metric, float | None] = {**NORMAL, Metric.RESP_RATE: None, Metric.SPO2: 80.0}
    alerts = detect_window_alerts(snapshot(dict(NORMAL), last), None, NOW)
    assert not [a for a in alerts if a.type is AlertType.NEWS2]


def test_diastolic_bp_is_not_part_of_news2() -> None:
    last: dict[Metric, float | None] = {**NORMAL, Metric.DIASTOLIC_BP: None}
    avg = dict(NORMAL)
    last = {**last, Metric.RESP_RATE: 26.0, Metric.SPO2: 90.0, Metric.HEART_RATE: 135.0}
    alerts = detect_window_alerts(snapshot(avg, last), None, NOW)
    assert [a for a in alerts if a.type is AlertType.NEWS2]


def test_ml_anomaly_when_distance_above_threshold() -> None:
    avg = {**NORMAL, Metric.HEART_RATE: 80.0}  # distance 8 in standardized space
    alerts = detect_window_alerts(snapshot(avg), _model(threshold=3.0), NOW)
    ml = [a for a in alerts if a.type is AlertType.ML_ANOMALY]
    assert len(ml) == 1 and ml[0].severity is Severity.MEDIUM
    assert ml[0].score == 8.0


def test_ml_anomaly_needs_all_six_metrics_and_a_model() -> None:
    partial: dict[Metric, float | None] = {**NORMAL, Metric.TEMPERATURE: None}
    assert not [
        a
        for a in detect_window_alerts(snapshot(partial), _model(0.0), NOW)
        if a.type is AlertType.ML_ANOMALY
    ]
    assert not [
        a
        for a in detect_window_alerts(snapshot({**NORMAL, Metric.HEART_RATE: 90.0}), None, NOW)
        if a.type is AlertType.ML_ANOMALY
    ]


def test_alert_windows_come_from_snapshot() -> None:
    avg = {**NORMAL, Metric.HEART_RATE: 105.0}
    (alert,) = list(detect_window_alerts(snapshot(avg), None, NOW))
    assert (alert.window_start, alert.window_end, alert.detected_at) == (WS, WE, NOW)
