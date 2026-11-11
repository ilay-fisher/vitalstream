from datetime import datetime, timezone

from stream_processor.alerts import AlertType
from stream_processor.anomaly import AnomalyModel, Standardizer
from stream_processor.detection import (
    WindowSnapshot,
    detect_trend_alerts,
    detect_window_alerts,
    window_bounds,
)
from stream_processor.ewma import EwmaConfig
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


def test_window_bounds_floors_to_minute() -> None:
    ts = int(datetime(2026, 11, 11, 20, 15, 42, 500_000, tzinfo=timezone.utc).timestamp() * 1000)
    assert window_bounds(ts, 60) == (WS, WE)


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


CFG = EwmaConfig()


def _ms(minute: int, second: int = 0) -> int:
    return int(datetime(2026, 11, 11, 20, minute, second, tzinfo=timezone.utc).timestamp() * 1000)


def test_trend_alert_one_per_window_with_largest_z() -> None:
    warmup = [(_ms(0, s), 72.0 + (s % 3) * 0.4) for s in range(30)]
    state, alerts = detect_trend_alerts("P-0001", Metric.HEART_RATE, None, warmup, CFG, NOW)
    assert alerts == []
    spikes = [(_ms(1, 5), 110.0), (_ms(1, 6), 140.0), (_ms(1, 7), 72.0)]
    state, alerts = detect_trend_alerts("P-0001", Metric.HEART_RATE, state, spikes, CFG, NOW)
    assert len(alerts) == 1
    alert = alerts[0]
    assert alert.type is AlertType.TREND_ZSCORE and alert.severity is Severity.HIGH
    assert alert.window_start == datetime(2026, 11, 11, 20, 1, tzinfo=timezone.utc)
    assert alert.value in (110.0, 140.0)
    assert alert.score is not None and abs(alert.score) >= 3.0


def test_trend_alerts_in_different_windows_are_separate() -> None:
    warmup = [(_ms(0, s), 72.0 + (s % 3) * 0.4) for s in range(30)]
    state, _ = detect_trend_alerts("P-0001", Metric.HEART_RATE, None, warmup, CFG, NOW)
    spikes = [(_ms(1, 5), 150.0), (_ms(2, 5), 190.0)]
    _, alerts = detect_trend_alerts("P-0001", Metric.HEART_RATE, state, spikes, CFG, NOW)
    assert len({a.window_start for a in alerts}) == len(alerts) >= 1
