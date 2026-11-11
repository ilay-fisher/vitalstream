from __future__ import annotations

import pytest

from vitalstream_sim.news2 import news2_score


def test_normal_is_zero() -> None:
    assert news2_score(rr=16, spo2=98, sbp=120, hr=70, temp=36.8) == 0


@pytest.mark.parametrize(
    ("override", "expected"),
    [
        ({"rr": 8}, 3),
        ({"rr": 10}, 1),
        ({"rr": 22}, 2),
        ({"rr": 25}, 3),
        ({"spo2": 91}, 3),
        ({"spo2": 93}, 2),
        ({"spo2": 95}, 1),
        ({"sbp": 90}, 3),
        ({"sbp": 100}, 2),
        ({"sbp": 110}, 1),
        ({"sbp": 220}, 3),
        ({"hr": 40}, 3),
        ({"hr": 50}, 1),
        ({"hr": 100}, 1),
        ({"hr": 120}, 2),
        ({"hr": 131}, 3),
        ({"temp": 35.0}, 3),
        ({"temp": 35.5}, 1),
        ({"temp": 38.5}, 1),
        ({"temp": 39.1}, 2),
    ],
)
def test_single_parameter_bands(override: dict[str, float], expected: int) -> None:
    params = {"rr": 16.0, "spo2": 98.0, "sbp": 120.0, "hr": 70.0, "temp": 36.8} | override
    assert news2_score(**params) == expected
