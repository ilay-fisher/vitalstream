"""National Early Warning Score 2 (Royal College of Physicians, 2017).

A pure function so that the agent, the MCP server and the unit tests share exactly one
implementation of the chart. Every band edge is inclusive on the upper bound, which matches
the integer/one-decimal precision that bedside monitors report.
"""

from dataclasses import dataclass
from enum import StrEnum
from typing import Any

_ALERT = "A"
_NOT_ALERT = frozenset({"C", "V", "P", "U"})

_PLAUSIBLE: dict[str, tuple[float, float]] = {
    "rr": (1, 80),
    "spo2": (30, 100),
    "sbp": (20, 300),
    "hr": (10, 300),
    "temp": (25, 45),
}


class RiskBand(StrEnum):
    """Clinical risk band derived from the aggregate score."""

    LOW = "LOW"
    LOW_MEDIUM = "LOW_MEDIUM"
    MEDIUM = "MEDIUM"
    HIGH = "HIGH"


_RESPONSES: dict[RiskBand, str] = {
    RiskBand.LOW: "Routine monitoring: minimum 12-hourly observations.",
    RiskBand.LOW_MEDIUM: (
        "Urgent ward-based response: a registered nurse reviews and decides on "
        "escalation to a clinician; increase observation frequency."
    ),
    RiskBand.MEDIUM: (
        "Urgent response: clinician with core competencies for acute illness reviews "
        "within the hour; hourly observations; consider critical care input."
    ),
    RiskBand.HIGH: (
        "Emergency response: immediate review by a critical-care-competent team, "
        "continuous monitoring, transfer to higher level of care."
    ),
}


@dataclass(frozen=True, slots=True)
class News2Result:
    """Outcome of a NEWS2 calculation including the per-parameter breakdown."""

    total: int
    risk: RiskBand
    components: dict[str, int]
    response: str
    inputs: dict[str, Any]

    def to_dict(self) -> dict[str, Any]:
        """Return a JSON-serialisable representation for tools and API payloads."""
        return {
            "total": self.total,
            "risk": self.risk.value,
            "components": dict(self.components),
            "response": self.response,
            "inputs": dict(self.inputs),
        }


def _rr_points(rr: float) -> int:
    if rr <= 8:
        return 3
    if rr <= 11:
        return 1
    if rr <= 20:
        return 0
    if rr <= 24:
        return 2
    return 3


def _spo2_scale1_points(spo2: float) -> int:
    if spo2 <= 91:
        return 3
    if spo2 <= 93:
        return 2
    if spo2 <= 95:
        return 1
    return 0


def _spo2_scale2_points(spo2: float, on_oxygen: bool) -> int:
    if spo2 <= 83:
        return 3
    if spo2 <= 85:
        return 2
    if spo2 <= 87:
        return 1
    if spo2 <= 92 or not on_oxygen:
        return 0
    if spo2 <= 94:
        return 1
    if spo2 <= 96:
        return 2
    return 3


def _sbp_points(sbp: float) -> int:
    if sbp <= 90:
        return 3
    if sbp <= 100:
        return 2
    if sbp <= 110:
        return 1
    if sbp <= 219:
        return 0
    return 3


def _hr_points(hr: float) -> int:
    if hr <= 40:
        return 3
    if hr <= 50:
        return 1
    if hr <= 90:
        return 0
    if hr <= 110:
        return 1
    if hr <= 130:
        return 2
    return 3


def _temp_points(temp: float) -> int:
    if temp <= 35.0:
        return 3
    if temp <= 36.0:
        return 1
    if temp <= 38.0:
        return 0
    if temp <= 39.0:
        return 1
    return 2


def _risk_band(total: int, components: dict[str, int]) -> RiskBand:
    if total >= 7:
        return RiskBand.HIGH
    if total >= 5:
        return RiskBand.MEDIUM
    if 3 in components.values():
        return RiskBand.LOW_MEDIUM
    return RiskBand.LOW


def _validate(values: dict[str, float]) -> None:
    for name, value in values.items():
        low, high = _PLAUSIBLE[name]
        if not low <= value <= high:
            raise ValueError(f"{name}={value} is not a plausible value (expected {low}-{high})")


def compute_news2(
    *,
    rr: float,
    spo2: float,
    sbp: float,
    hr: float,
    temp: float,
    consciousness: str = _ALERT,
    supplemental_o2: bool = False,
    spo2_scale: int = 1,
) -> News2Result:
    """Score a set of observations with NEWS2.

    Args:
        rr: respiratory rate, breaths/min.
        spo2: peripheral oxygen saturation, percent.
        sbp: systolic blood pressure, mmHg.
        hr: heart rate, beats/min.
        temp: temperature, degrees Celsius.
        consciousness: ACVPU level; anything other than ``A`` scores 3.
        supplemental_o2: patient is on supplemental oxygen (adds 2).
        spo2_scale: 1 (default) or 2 (target 88-92 %, e.g. hypercapnic respiratory failure).

    Raises:
        ValueError: on implausible vital values or unknown ``consciousness``/``spo2_scale``.
    """
    level = consciousness.strip().upper()
    if level != _ALERT and level not in _NOT_ALERT:
        raise ValueError(f"consciousness must be one of A, C, V, P, U (got {consciousness!r})")
    if spo2_scale not in (1, 2):
        raise ValueError(f"spo2_scale must be 1 or 2 (got {spo2_scale!r})")
    _validate({"rr": rr, "spo2": spo2, "sbp": sbp, "hr": hr, "temp": temp})

    spo2_points = (
        _spo2_scale1_points(spo2)
        if spo2_scale == 1
        else _spo2_scale2_points(spo2, supplemental_o2)
    )
    components = {
        "rr": _rr_points(rr),
        "spo2": spo2_points,
        "supplemental_o2": 2 if supplemental_o2 else 0,
        "sbp": _sbp_points(sbp),
        "hr": _hr_points(hr),
        "consciousness": 0 if level == _ALERT else 3,
        "temp": _temp_points(temp),
    }
    total = sum(components.values())
    risk = _risk_band(total, components)
    return News2Result(
        total=total,
        risk=risk,
        components=components,
        response=_RESPONSES[risk],
        inputs={
            "rr": rr,
            "spo2": spo2,
            "sbp": sbp,
            "hr": hr,
            "temp": temp,
            "consciousness": level,
            "supplemental_o2": supplemental_o2,
            "spo2_scale": spo2_scale,
        },
    )
