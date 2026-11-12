import json
import uuid
from datetime import datetime, timezone

import pytest

from stream_processor.alerts import (
    ALERT_NAMESPACE,
    AlertType,
    alert_id,
    format_instant,
    make_anomaly_alert,
    make_news2_alert,
    make_threshold_alert,
    make_trend_alert,
)
from stream_processor.ewma import TrendHit
from stream_processor.news2 import compute_news2
from stream_processor.vitals import Metric, Severity

WS = datetime(2026, 11, 11, 20, 15, 0, tzinfo=timezone.utc)
WE = datetime(2026, 11, 11, 20, 16, 0, tzinfo=timezone.utc)
NOW = datetime(2026, 11, 11, 20, 16, 2, 10_000, tzinfo=timezone.utc)


def test_format_instant_seconds_and_millis() -> None:
    assert format_instant(WS) == "2026-11-11T20:15:00Z"
    assert format_instant(NOW, millis=True) == "2026-11-11T20:16:02.010Z"


def test_format_instant_converts_to_utc() -> None:
    from datetime import timedelta

    local = datetime(2026, 11, 11, 22, 15, 0, tzinfo=timezone(timedelta(hours=2)))
    assert format_instant(local) == "2026-11-11T20:15:00Z"


def test_alert_id_is_uuid5_of_pipe_joined_key() -> None:
    expected = uuid.uuid5(ALERT_NAMESPACE, "P-0001|NEWS2||2026-11-11T20:15:00Z")
    assert alert_id("P-0001", AlertType.NEWS2, None, WS) == str(expected)


def test_alert_id_with_metric() -> None:
    expected = uuid.uuid5(ALERT_NAMESPACE, "P-0001|THRESHOLD|HEART_RATE|2026-11-11T20:15:00Z")
    assert alert_id("P-0001", AlertType.THRESHOLD, Metric.HEART_RATE, WS) == str(expected)


def test_alert_id_is_deterministic_and_discriminating() -> None:
    a = alert_id("P-0001", AlertType.THRESHOLD, Metric.HEART_RATE, WS)
    assert a == alert_id("P-0001", AlertType.THRESHOLD, Metric.HEART_RATE, WS)
    assert a != alert_id("P-0002", AlertType.THRESHOLD, Metric.HEART_RATE, WS)
    assert a != alert_id("P-0001", AlertType.TREND_ZSCORE, Metric.HEART_RATE, WS)
    assert a != alert_id("P-0001", AlertType.THRESHOLD, Metric.SPO2, WS)
    assert a != alert_id("P-0001", AlertType.THRESHOLD, Metric.HEART_RATE, WE)


def test_threshold_alert_none_when_normal() -> None:
    assert make_threshold_alert("P-0001", Metric.HEART_RATE, 72.0, WS, WE, NOW) is None


def test_threshold_alert_medium() -> None:
    alert = make_threshold_alert("P-0001", Metric.HEART_RATE, 105.0, WS, WE, NOW)
    assert alert is not None
    assert alert.severity is Severity.MEDIUM
    assert alert.type is AlertType.THRESHOLD
    assert alert.metric is Metric.HEART_RATE
    assert alert.value == 105.0
    assert "HEART_RATE" in alert.message and "105" in alert.message


def test_threshold_alert_critical() -> None:
    alert = make_threshold_alert("P-0001", Metric.SPO2, 85.0, WS, WE, NOW)
    assert alert is not None
    assert alert.severity is Severity.CRITICAL


def test_trend_alert_is_medium_with_zscore_and_window_mean() -> None:
    hit = TrendHit(value=104.2, trend_mean=88.9, z=4.1, delta=15.3, breaches=3)
    alert = make_trend_alert("P-0001", Metric.HEART_RATE, hit, WS, WE, NOW)
    assert alert.severity is Severity.MEDIUM
    assert alert.type is AlertType.TREND_ZSCORE
    assert alert.score == 4.1
    assert alert.value == 104.2
    assert alert.message == "HEART_RATE rising: 104.2 vs trend 88.9 (+15.3, z=4.1, 3 of 5 min)"


def test_trend_alert_message_for_falling_metric() -> None:
    hit = TrendHit(value=91.0, trend_mean=97.4, z=-5.26, delta=-6.4, breaches=4)
    alert = make_trend_alert("P-0001", Metric.SPO2, hit, WS, WE, NOW)
    assert alert.message == "SPO2 falling: 91.0 vs trend 97.4 (-6.4, z=-5.3, 4 of 5 min)"


def test_trend_alert_id_scheme_is_unchanged() -> None:
    hit = TrendHit(value=104.2, trend_mean=88.9, z=4.1, delta=15.3, breaches=3)
    alert = make_trend_alert("P-0001", Metric.HEART_RATE, hit, WS, WE, NOW)
    assert alert.alert_id == alert_id("P-0001", AlertType.TREND_ZSCORE, Metric.HEART_RATE, WS)


def test_news2_alert_none_below_thresholds() -> None:
    result = compute_news2(rr=16, spo2=98, sbp=120, hr=72, temp=36.8)
    assert make_news2_alert("P-0001", result, WS, WE, NOW) is None


def test_news2_alert_high_and_critical() -> None:
    high = compute_news2(rr=22, spo2=94, sbp=105, hr=95, temp=38.5)
    alert = make_news2_alert("P-0001", high, WS, WE, NOW)
    assert alert is not None and alert.severity is Severity.HIGH
    assert alert.score == 6.0 and alert.metric is None and alert.value is None
    critical = compute_news2(rr=25, spo2=91, sbp=120, hr=131, temp=36.8)
    alert = make_news2_alert("P-0001", critical, WS, WE, NOW)
    assert alert is not None and alert.severity is Severity.CRITICAL
    assert alert.message == "NEWS2 score 9 (RR 25, SpO2 91, HR 131)"


def test_news2_alert_medium_for_single_parameter_three() -> None:
    result = compute_news2(rr=16, spo2=98, sbp=120, hr=72, temp=34.0)
    alert = make_news2_alert("P-0001", result, WS, WE, NOW)
    assert alert is not None and alert.severity is Severity.MEDIUM


def test_anomaly_alert_is_medium() -> None:
    alert = make_anomaly_alert("P-0001", 7.5, 4.1, WS, WE, NOW)
    assert alert.severity is Severity.MEDIUM
    assert alert.type is AlertType.ML_ANOMALY
    assert alert.score == 7.5
    assert "4.10" in alert.message


def test_to_json_matches_contract_shape() -> None:
    alert = make_news2_alert("P-0001", compute_news2(25, 91, 120, 131, 36.8), WS, WE, NOW)
    assert alert is not None
    payload = json.loads(alert.to_json())
    assert set(payload) == {
        "alertId", "patientId", "type", "severity", "metric", "value", "score",
        "message", "windowStart", "windowEnd", "detectedAt",
    }  # fmt: skip
    assert payload["alertId"] == alert.alert_id
    assert payload["type"] == "NEWS2"
    assert payload["severity"] == "CRITICAL"
    assert payload["metric"] is None and payload["value"] is None
    assert payload["score"] == 9.0
    assert payload["windowStart"] == "2026-11-11T20:15:00Z"
    assert payload["windowEnd"] == "2026-11-11T20:16:00Z"
    assert payload["detectedAt"] == "2026-11-11T20:16:02.010Z"


def test_to_json_threshold_has_metric_and_value() -> None:
    alert = make_threshold_alert("P-0001", Metric.SPO2, 85.0, WS, WE, NOW)
    assert alert is not None
    payload = json.loads(alert.to_json())
    assert payload["metric"] == "SPO2"
    assert payload["value"] == 85.0
    assert payload["score"] is None


def test_naive_datetimes_are_rejected() -> None:
    with pytest.raises(ValueError, match="timezone"):
        format_instant(datetime(2026, 1, 1))
