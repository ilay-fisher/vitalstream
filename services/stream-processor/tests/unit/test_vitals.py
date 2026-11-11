import pytest

from stream_processor.vitals import SPECS, Metric, Severity, is_valid_reading


def test_every_metric_has_a_spec() -> None:
    assert set(SPECS) == set(Metric)


@pytest.mark.parametrize(
    ("metric", "unit", "loinc", "valid", "normal"),
    [
        (Metric.HEART_RATE, "/min", "8867-4", (20, 250), (51, 90)),
        (Metric.SPO2, "%", "59408-5", (50, 100), (96, 100)),
        (Metric.RESP_RATE, "/min", "9279-1", (4, 60), (12, 20)),
        (Metric.SYSTOLIC_BP, "mm[Hg]", "8480-6", (50, 260), (111, 219)),
        (Metric.DIASTOLIC_BP, "mm[Hg]", "8462-4", (20, 160), (60, 90)),
        (Metric.TEMPERATURE, "Cel", "8310-5", (30, 43), (36.1, 38.0)),
    ],
)
def test_specs_match_contract_table(
    metric: Metric,
    unit: str,
    loinc: str,
    valid: tuple[float, float],
    normal: tuple[float, float],
) -> None:
    spec = SPECS[metric]
    assert (spec.unit, spec.loinc) == (unit, loinc)
    assert (spec.valid_min, spec.valid_max) == valid
    assert (spec.normal_min, spec.normal_max) == normal


def test_severity_ranking_orders_low_to_critical() -> None:
    ordered = [Severity.LOW, Severity.MEDIUM, Severity.HIGH, Severity.CRITICAL]
    assert sorted(ordered, key=lambda s: s.rank, reverse=True) == ordered[::-1]
    assert [s.rank for s in ordered] == [1, 2, 3, 4]


@pytest.mark.parametrize(
    ("metric", "value", "expected"),
    [
        (Metric.HEART_RATE, 20, True),
        (Metric.HEART_RATE, 250, True),
        (Metric.HEART_RATE, 19.9, False),
        (Metric.HEART_RATE, 250.1, False),
        (Metric.SPO2, 100, True),
        (Metric.SPO2, 101, False),
        (Metric.TEMPERATURE, 29.9, False),
    ],
)
def test_is_valid_reading(metric: Metric, value: float, expected: bool) -> None:
    assert is_valid_reading(metric, value) is expected


def test_is_valid_reading_rejects_nan() -> None:
    assert is_valid_reading(Metric.HEART_RATE, float("nan")) is False
