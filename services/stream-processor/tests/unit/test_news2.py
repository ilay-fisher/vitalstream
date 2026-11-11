"""Exhaustive tests of the RCP NEWS2 scale 1 banding (room air, patient Alert)."""

import pytest

from stream_processor.news2 import (
    News2Result,
    compute_news2,
    news2_severity,
    score_hr,
    score_rr,
    score_sbp,
    score_spo2,
    score_temp,
)
from stream_processor.vitals import Severity


@pytest.mark.parametrize(
    ("rr", "points"),
    [
        (4, 3),
        (8, 3),
        (8.4, 3),
        (8.5, 1),
        (9, 1),
        (10, 1),
        (11, 1),
        (11.4, 1),
        (11.5, 0),
        (12, 0),
        (16, 0),
        (20, 0),
        (20.4, 0),
        (20.5, 2),
        (21, 2),
        (22, 2),
        (24, 2),
        (24.4, 2),
        (24.5, 3),
        (25, 3),
        (40, 3),
        (60, 3),
    ],
)
def test_score_rr(rr: float, points: int) -> None:
    assert score_rr(rr) == points


@pytest.mark.parametrize(
    ("spo2", "points"),
    [
        (50, 3),
        (85, 3),
        (91, 3),
        (91.4, 3),
        (91.5, 2),
        (92, 2),
        (93, 2),
        (93.4, 2),
        (93.5, 1),
        (94, 1),
        (95, 1),
        (95.4, 1),
        (95.5, 0),
        (96, 0),
        (98, 0),
        (100, 0),
    ],
)
def test_score_spo2_scale_1(spo2: float, points: int) -> None:
    assert score_spo2(spo2) == points


@pytest.mark.parametrize(
    ("sbp", "points"),
    [
        (50, 3),
        (80, 3),
        (90, 3),
        (90.4, 3),
        (90.5, 2),
        (91, 2),
        (100, 2),
        (100.4, 2),
        (100.5, 1),
        (101, 1),
        (110, 1),
        (110.4, 1),
        (110.5, 0),
        (111, 0),
        (150, 0),
        (219, 0),
        (219.4, 0),
        (219.5, 3),
        (220, 3),
        (260, 3),
    ],
)
def test_score_sbp(sbp: float, points: int) -> None:
    assert score_sbp(sbp) == points


@pytest.mark.parametrize(
    ("hr", "points"),
    [
        (20, 3),
        (40, 3),
        (40.4, 3),
        (40.5, 1),
        (41, 1),
        (50, 1),
        (50.4, 1),
        (50.5, 0),
        (51, 0),
        (70, 0),
        (90, 0),
        (90.4, 0),
        (90.5, 1),
        (91, 1),
        (110, 1),
        (110.4, 1),
        (110.5, 2),
        (111, 2),
        (130, 2),
        (130.4, 2),
        (130.5, 3),
        (131, 3),
        (200, 3),
        (250, 3),
    ],
)
def test_score_hr(hr: float, points: int) -> None:
    assert score_hr(hr) == points


@pytest.mark.parametrize(
    ("temp", "points"),
    [
        (30.0, 3),
        (34.9, 3),
        (35.0, 3),
        (35.04, 3),
        (35.05, 1),
        (35.1, 1),
        (35.5, 1),
        (36.0, 1),
        (36.04, 1),
        (36.05, 0),
        (36.1, 0),
        (37.0, 0),
        (38.0, 0),
        (38.04, 0),
        (38.05, 1),
        (38.1, 1),
        (38.5, 1),
        (39.0, 1),
        (39.04, 1),
        (39.05, 2),
        (39.1, 2),
        (40.0, 2),
        (43.0, 2),
    ],
)
def test_score_temp(temp: float, points: int) -> None:
    assert score_temp(temp) == points


def test_all_normal_scores_zero() -> None:
    result = compute_news2(rr=16, spo2=98, sbp=120, hr=72, temp=36.8)
    assert result == News2Result(
        total=0,
        rr=0,
        spo2=0,
        sbp=0,
        hr=0,
        temp=0,
        values={"RR": 16, "SpO2": 98, "SBP": 120, "HR": 72, "Temp": 36.8},
    )
    assert result.max_single == 0
    assert result.contributing() == []


def test_critical_example_scores_seven() -> None:
    result = compute_news2(rr=25, spo2=93, sbp=120, hr=111, temp=36.8)
    assert (result.rr, result.spo2, result.hr) == (3, 2, 2)
    assert result.total == 7


def test_total_is_sum_of_parts() -> None:
    result = compute_news2(rr=22, spo2=94, sbp=105, hr=95, temp=38.5)
    assert (result.rr, result.spo2, result.sbp, result.hr, result.temp) == (2, 1, 1, 1, 1)
    assert result.total == 6
    assert result.max_single == 2


def test_maximum_without_consciousness_or_oxygen_is_fourteen() -> None:
    # 3 + 3 + 3 + 3 + 2: temperature tops out at 2 points.
    result = compute_news2(rr=30, spo2=85, sbp=80, hr=140, temp=40)
    assert result.total == 14


def test_contributing_lists_only_scoring_parameters_in_fixed_order() -> None:
    result = compute_news2(rr=25, spo2=91, sbp=120, hr=131, temp=36.8)
    assert result.contributing() == ["RR 25", "SpO2 91", "HR 131"]


def test_contributing_formats_temperature_with_one_decimal() -> None:
    result = compute_news2(rr=16, spo2=98, sbp=120, hr=72, temp=39.2)
    assert result.contributing() == ["Temp 39.2"]


@pytest.mark.parametrize(
    ("total", "max_single", "expected"),
    [
        (0, 0, None),
        (1, 1, None),
        (2, 2, None),
        (3, 2, None),
        (3, 3, Severity.MEDIUM),
        (4, 3, Severity.MEDIUM),
        (4, 2, None),
        (5, 2, Severity.HIGH),
        (5, 3, Severity.HIGH),
        (6, 3, Severity.HIGH),
        (7, 3, Severity.CRITICAL),
        (9, 3, Severity.CRITICAL),
        (14, 3, Severity.CRITICAL),
    ],
)
def test_news2_severity_mapping(total: int, max_single: int, expected: Severity | None) -> None:
    assert news2_severity(total, max_single) is expected
