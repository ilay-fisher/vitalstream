"""NEWS2 scoring against the Royal College of Physicians chart, boundary by boundary."""

import pytest

from copilot.news2 import RiskBand, compute_news2

NORMAL = {"rr": 16.0, "spo2": 98.0, "sbp": 120.0, "hr": 70.0, "temp": 37.0}


def _score(**overrides: float) -> int:
    return compute_news2(**{**NORMAL, **overrides}).total


@pytest.mark.parametrize(
    ("rr", "expected"),
    [(4, 3), (8, 3), (9, 1), (11, 1), (12, 0), (20, 0), (21, 2), (24, 2), (25, 3), (40, 3)],
)
def test_respiratory_rate_bands(rr: float, expected: int) -> None:
    assert _score(rr=rr) == expected


@pytest.mark.parametrize(
    ("spo2", "expected"),
    [(70, 3), (91, 3), (92, 2), (93, 2), (94, 1), (95, 1), (96, 0), (100, 0)],
)
def test_spo2_scale1_bands(spo2: float, expected: int) -> None:
    assert _score(spo2=spo2) == expected


@pytest.mark.parametrize(
    ("spo2", "on_o2", "expected_spo2_points"),
    [
        (83, False, 3),
        (84, False, 2),
        (85, False, 2),
        (86, False, 1),
        (87, False, 1),
        (88, False, 0),
        (92, False, 0),
        (93, False, 0),
        (97, False, 0),
        (88, True, 0),
        (92, True, 0),
        (93, True, 1),
        (94, True, 1),
        (95, True, 2),
        (96, True, 2),
        (97, True, 3),
        (99, True, 3),
    ],
)
def test_spo2_scale2_bands(spo2: float, on_o2: bool, expected_spo2_points: int) -> None:
    result = compute_news2(**{**NORMAL, "spo2": spo2}, spo2_scale=2, supplemental_o2=on_o2)
    assert result.components["spo2"] == expected_spo2_points
    assert result.total == expected_spo2_points + (2 if on_o2 else 0)


@pytest.mark.parametrize(
    ("sbp", "expected"),
    [(50, 3), (90, 3), (91, 2), (100, 2), (101, 1), (110, 1), (111, 0), (219, 0), (220, 3), (260, 3)],
)
def test_systolic_bp_bands(sbp: float, expected: int) -> None:
    assert _score(sbp=sbp) == expected


@pytest.mark.parametrize(
    ("hr", "expected"),
    [
        (30, 3),
        (40, 3),
        (41, 1),
        (50, 1),
        (51, 0),
        (90, 0),
        (91, 1),
        (110, 1),
        (111, 2),
        (130, 2),
        (131, 3),
        (200, 3),
    ],
)
def test_heart_rate_bands(hr: float, expected: int) -> None:
    assert _score(hr=hr) == expected


@pytest.mark.parametrize(
    ("temp", "expected"),
    [
        (34.0, 3),
        (35.0, 3),
        (35.1, 1),
        (36.0, 1),
        (36.1, 0),
        (38.0, 0),
        (38.1, 1),
        (39.0, 1),
        (39.1, 2),
        (41.0, 2),
    ],
)
def test_temperature_bands(temp: float, expected: int) -> None:
    assert _score(temp=temp) == expected


@pytest.mark.parametrize("level", ["V", "P", "U", "C", "v"])
def test_consciousness_not_alert_scores_three(level: str) -> None:
    assert compute_news2(**NORMAL, consciousness=level).total == 3


def test_alert_consciousness_scores_zero() -> None:
    assert compute_news2(**NORMAL, consciousness="A").total == 0


def test_supplemental_oxygen_adds_two_points() -> None:
    assert compute_news2(**NORMAL, supplemental_o2=True).total == 2


def test_all_normal_is_low_risk_zero() -> None:
    result = compute_news2(**NORMAL)
    assert result.total == 0
    assert result.risk is RiskBand.LOW


@pytest.mark.parametrize(
    ("overrides", "total", "risk"),
    [
        ({"rr": 22.0}, 2, RiskBand.LOW),
        ({"rr": 22.0, "hr": 95.0, "temp": 38.5}, 4, RiskBand.LOW),
        ({"rr": 22.0, "hr": 95.0, "temp": 38.5, "sbp": 105.0}, 5, RiskBand.MEDIUM),
        ({"rr": 22.0, "hr": 120.0, "temp": 38.5, "spo2": 94.0}, 6, RiskBand.MEDIUM),
        ({"rr": 25.0, "spo2": 91.0, "hr": 131.0}, 9, RiskBand.HIGH),
        ({"rr": 22.0, "hr": 120.0, "spo2": 93.0, "sbp": 100.0}, 8, RiskBand.HIGH),
        ({"rr": 22.0, "hr": 115.0, "spo2": 93.0, "sbp": 95.0, "temp": 38.5}, 9, RiskBand.HIGH),
    ],
)
def test_aggregate_total_and_risk_band(
    overrides: dict[str, float], total: int, risk: RiskBand
) -> None:
    result = compute_news2(**{**NORMAL, **overrides})
    assert result.total == total
    assert result.risk is risk


def test_exactly_seven_is_high() -> None:
    result = compute_news2(**{**NORMAL, "rr": 22.0, "hr": 120.0, "spo2": 93.0, "sbp": 105.0})
    assert result.total == 2 + 2 + 2 + 1
    assert result.risk is RiskBand.HIGH


def test_single_red_score_raises_risk_to_low_medium() -> None:
    result = compute_news2(**{**NORMAL, "temp": 35.0})
    assert result.total == 3
    assert result.risk is RiskBand.LOW_MEDIUM
    assert "urgent" in result.response.lower()


def test_single_red_score_inside_medium_total_stays_medium() -> None:
    result = compute_news2(**{**NORMAL, "temp": 35.0, "rr": 22.0})
    assert result.total == 3 + 2
    assert result.risk is RiskBand.MEDIUM


def test_high_total_has_emergency_response() -> None:
    result = compute_news2(rr=26, spo2=90, sbp=88, hr=135, temp=39.5)
    assert result.total >= 7
    assert result.risk is RiskBand.HIGH
    assert "emergency" in result.response.lower()


def test_components_report_every_parameter() -> None:
    result = compute_news2(rr=25, spo2=91, sbp=120, hr=131, temp=37.0)
    assert result.components == {
        "rr": 3,
        "spo2": 3,
        "supplemental_o2": 0,
        "sbp": 0,
        "hr": 3,
        "consciousness": 0,
        "temp": 0,
    }


def test_to_dict_is_json_friendly() -> None:
    data = compute_news2(rr=25, spo2=91, sbp=120, hr=131, temp=37.0).to_dict()
    assert data["total"] == 9
    assert data["risk"] == "HIGH"
    assert isinstance(data["response"], str)
    assert data["inputs"]["hr"] == 131


@pytest.mark.parametrize("bad", [{"rr": -1.0}, {"spo2": 120.0}, {"temp": 5.0}, {"hr": 0.0}, {"sbp": -5.0}])
def test_implausible_values_rejected(bad: dict[str, float]) -> None:
    with pytest.raises(ValueError, match="plausible"):
        compute_news2(**{**NORMAL, **bad})


def test_unknown_consciousness_or_scale_rejected() -> None:
    with pytest.raises(ValueError, match="consciousness"):
        compute_news2(**NORMAL, consciousness="X")
    with pytest.raises(ValueError, match="spo2_scale"):
        compute_news2(**NORMAL, spo2_scale=3)
