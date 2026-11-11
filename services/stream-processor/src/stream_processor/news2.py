"""National Early Warning Score 2 (Royal College of Physicians, 2017), SpO2 scale 1.

The patient is assumed to breathe room air and to be Alert, so the "air or oxygen" and
"consciousness" rows contribute 0. Observation charts record whole numbers (temperature to one
decimal), so each value is rounded half-up to the chart resolution before banding; that closes the
gaps between bands for the continuous window averages the stream produces.
"""

from __future__ import annotations

import math
from dataclasses import dataclass
from decimal import ROUND_HALF_UP, Decimal

from stream_processor.vitals import Severity


def _round_int(value: float) -> int:
    return math.floor(value + 0.5)


def _round_tenth(value: float) -> Decimal:
    return Decimal(str(value)).quantize(Decimal("0.1"), rounding=ROUND_HALF_UP)


def score_rr(rr: float) -> int:
    """Respiration rate (/min): <=8 -> 3, 9-11 -> 1, 12-20 -> 0, 21-24 -> 2, >=25 -> 3."""
    v = _round_int(rr)
    if v <= 8:
        return 3
    if v <= 11:
        return 1
    if v <= 20:
        return 0
    if v <= 24:
        return 2
    return 3


def score_spo2(spo2: float) -> int:
    """SpO2 scale 1 (%): <=91 -> 3, 92-93 -> 2, 94-95 -> 1, >=96 -> 0."""
    v = _round_int(spo2)
    if v <= 91:
        return 3
    if v <= 93:
        return 2
    if v <= 95:
        return 1
    return 0


def score_sbp(sbp: float) -> int:
    """Systolic BP (mmHg): <=90 -> 3, 91-100 -> 2, 101-110 -> 1, 111-219 -> 0, >=220 -> 3."""
    v = _round_int(sbp)
    if v <= 90:
        return 3
    if v <= 100:
        return 2
    if v <= 110:
        return 1
    if v <= 219:
        return 0
    return 3


def score_hr(hr: float) -> int:
    """Pulse (/min): <=40 -> 3, 41-50 -> 1, 51-90 -> 0, 91-110 -> 1, 111-130 -> 2, >=131 -> 3."""
    v = _round_int(hr)
    if v <= 40:
        return 3
    if v <= 50:
        return 1
    if v <= 90:
        return 0
    if v <= 110:
        return 1
    if v <= 130:
        return 2
    return 3


def score_temp(temp: float) -> int:
    """Temperature (Cel): <=35.0 -> 3, 35.1-36.0 -> 1, 36.1-38.0 -> 0, 38.1-39.0 -> 1, else 2."""
    v = _round_tenth(temp)
    if v <= Decimal("35.0"):
        return 3
    if v <= Decimal("36.0"):
        return 1
    if v <= Decimal("38.0"):
        return 0
    if v <= Decimal("39.0"):
        return 1
    return 2


@dataclass(frozen=True)
class News2Result:
    """Per-parameter sub-scores, their total and the raw inputs (for the alert message)."""

    total: int
    rr: int
    spo2: int
    sbp: int
    hr: int
    temp: int
    values: dict[str, float]

    @property
    def max_single(self) -> int:
        """Highest single-parameter sub-score (3 means "red score" in RCP terms)."""
        return max(self.rr, self.spo2, self.sbp, self.hr, self.temp)

    def contributing(self) -> list[str]:
        """Return "<label> <value>" for each parameter that scored, in chart order."""
        parts = [
            ("RR", self.rr),
            ("SpO2", self.spo2),
            ("SBP", self.sbp),
            ("HR", self.hr),
            ("Temp", self.temp),
        ]
        return [f"{label} {_format_value(label, self.values[label])}" for label, p in parts if p]


def _format_value(label: str, value: float) -> str:
    if label == "Temp":
        return f"{value:.1f}"
    return str(_round_int(value))


def compute_news2(rr: float, spo2: float, sbp: float, hr: float, temp: float) -> News2Result:
    """Score the five physiological parameters and total them."""
    s_rr, s_spo2, s_sbp, s_hr, s_temp = (
        score_rr(rr),
        score_spo2(spo2),
        score_sbp(sbp),
        score_hr(hr),
        score_temp(temp),
    )
    return News2Result(
        total=s_rr + s_spo2 + s_sbp + s_hr + s_temp,
        rr=s_rr,
        spo2=s_spo2,
        sbp=s_sbp,
        hr=s_hr,
        temp=s_temp,
        values={"RR": rr, "SpO2": spo2, "SBP": sbp, "HR": hr, "Temp": temp},
    )


def news2_severity(total: int, max_single: int) -> Severity | None:
    """Map a score to an alert severity per CONTRACTS 3.3.

    Total >= 7 -> CRITICAL, 5-6 -> HIGH, otherwise any single parameter scoring 3 -> MEDIUM.
    """
    if total >= 7:
        return Severity.CRITICAL
    if total >= 5:
        return Severity.HIGH
    if max_single >= 3:
        return Severity.MEDIUM
    return None
