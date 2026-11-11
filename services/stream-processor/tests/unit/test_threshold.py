import pytest

from stream_processor.threshold import evaluate_threshold
from stream_processor.vitals import Metric, Severity


@pytest.mark.parametrize(
    ("metric", "value"),
    [
        (Metric.HEART_RATE, 51),
        (Metric.HEART_RATE, 90),
        (Metric.HEART_RATE, 72.5),
        (Metric.SPO2, 96),
        (Metric.SPO2, 100),
        (Metric.RESP_RATE, 12),
        (Metric.RESP_RATE, 20),
        (Metric.SYSTOLIC_BP, 111),
        (Metric.SYSTOLIC_BP, 219),
        (Metric.DIASTOLIC_BP, 60),
        (Metric.DIASTOLIC_BP, 90),
        (Metric.TEMPERATURE, 36.1),
        (Metric.TEMPERATURE, 38.0),
    ],
)
def test_inside_normal_range_no_alert(metric: Metric, value: float) -> None:
    assert evaluate_threshold(metric, value) is None


@pytest.mark.parametrize(
    ("metric", "value"),
    [
        (Metric.HEART_RATE, 50.9),
        (Metric.HEART_RATE, 40),  # critical band is strictly < 40
        (Metric.HEART_RATE, 91),
        (Metric.HEART_RATE, 130),  # critical band is strictly > 130
        (Metric.SPO2, 95.9),
        (Metric.SPO2, 90),  # critical is strictly < 90
        (Metric.RESP_RATE, 11.9),
        (Metric.RESP_RATE, 8),
        (Metric.RESP_RATE, 20.1),
        (Metric.RESP_RATE, 25),
        (Metric.SYSTOLIC_BP, 110.9),
        (Metric.SYSTOLIC_BP, 90),
        (Metric.SYSTOLIC_BP, 220),  # no upper critical band for SBP
        (Metric.SYSTOLIC_BP, 260),
        (Metric.DIASTOLIC_BP, 59),
        (Metric.DIASTOLIC_BP, 20),  # DBP has no critical band
        (Metric.DIASTOLIC_BP, 91),
        (Metric.DIASTOLIC_BP, 160),
        (Metric.TEMPERATURE, 36.0),
        (Metric.TEMPERATURE, 35),
        (Metric.TEMPERATURE, 38.1),
        (Metric.TEMPERATURE, 39.1),
    ],
)
def test_outside_normal_but_not_critical_is_medium(metric: Metric, value: float) -> None:
    assert evaluate_threshold(metric, value) is Severity.MEDIUM


@pytest.mark.parametrize(
    ("metric", "value"),
    [
        (Metric.HEART_RATE, 39.9),
        (Metric.HEART_RATE, 20),
        (Metric.HEART_RATE, 130.1),
        (Metric.HEART_RATE, 250),
        (Metric.SPO2, 89.9),
        (Metric.SPO2, 50),
        (Metric.RESP_RATE, 7.9),
        (Metric.RESP_RATE, 25.1),
        (Metric.SYSTOLIC_BP, 89.9),
        (Metric.SYSTOLIC_BP, 50),
        (Metric.TEMPERATURE, 34.9),
        (Metric.TEMPERATURE, 39.2),
        (Metric.TEMPERATURE, 43),
    ],
)
def test_critical_bands_are_critical(metric: Metric, value: float) -> None:
    assert evaluate_threshold(metric, value) is Severity.CRITICAL
