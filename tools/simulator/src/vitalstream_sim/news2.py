"""NEWS2 (Royal College of Physicians) aggregate score, SpO2 scale 1, patient alert.

Used to prove that deteriorating scenarios cross the clinical thresholds the stream-processor
alerts on (CONTRACTS 3.3). It mirrors the detection rule, it is not a clinical tool.
"""

from __future__ import annotations


def _resp_rate(rr: float) -> int:
    value = round(rr)
    if value <= 8 or value >= 25:
        return 3
    if value >= 21:
        return 2
    return 1 if value <= 11 else 0


def _spo2(spo2: float) -> int:
    value = round(spo2)
    if value <= 91:
        return 3
    if value <= 93:
        return 2
    return 1 if value <= 95 else 0


def _systolic(sbp: float) -> int:
    value = round(sbp)
    if value <= 90 or value >= 220:
        return 3
    if value <= 100:
        return 2
    return 1 if value <= 110 else 0


def _heart_rate(hr: float) -> int:
    value = round(hr)
    if value <= 40 or value >= 131:
        return 3
    if value >= 111:
        return 2
    if value >= 91 or value <= 50:
        return 1
    return 0


def _temperature(temp: float) -> int:
    if temp <= 35.0:
        return 3
    if temp >= 39.1:
        return 2
    if temp <= 36.0 or temp >= 38.1:
        return 1
    return 0


def news2_score(*, rr: float, spo2: float, sbp: float, hr: float, temp: float) -> int:
    """Total NEWS2 for room air and a patient who is alert."""
    return _resp_rate(rr) + _spo2(spo2) + _systolic(sbp) + _heart_rate(hr) + _temperature(temp)
