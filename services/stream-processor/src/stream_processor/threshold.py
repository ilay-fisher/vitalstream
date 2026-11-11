"""THRESHOLD detector: 1-minute window average against normal and critical bands."""

from __future__ import annotations

from stream_processor.vitals import SPECS, Metric, Severity


def evaluate_threshold(metric: Metric, window_avg: float) -> Severity | None:
    """Classify a window average.

    Returns CRITICAL when the average is beyond a critical band (strict inequality, so a value
    exactly on the band edge is still MEDIUM), MEDIUM when merely outside the normal range, and
    None when normal. Using the window average rather than single readings keeps one noisy
    sample from paging a clinician.
    """
    spec = SPECS[metric]
    if spec.critical_low is not None and window_avg < spec.critical_low:
        return Severity.CRITICAL
    if spec.critical_high is not None and window_avg > spec.critical_high:
        return Severity.CRITICAL
    if window_avg < spec.normal_min or window_avg > spec.normal_max:
        return Severity.MEDIUM
    return None
