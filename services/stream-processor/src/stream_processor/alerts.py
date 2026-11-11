"""AlertEvent construction (CONTRACTS 3.2) with deterministic ids."""

from __future__ import annotations

import json
import uuid
from dataclasses import dataclass
from datetime import datetime, timezone
from enum import Enum

from stream_processor.news2 import News2Result, news2_severity
from stream_processor.threshold import evaluate_threshold
from stream_processor.vitals import Metric, Severity

# CONTRACTS 3.2 fixes the key (`patientId|type|metric|windowStart`) but not the UUIDv5 namespace.
# NAMESPACE_URL is used; any consumer that wants to recompute ids must use the same one.
ALERT_NAMESPACE = uuid.NAMESPACE_URL


class AlertType(str, Enum):
    """Detector that raised the alert."""

    THRESHOLD = "THRESHOLD"
    TREND_ZSCORE = "TREND_ZSCORE"
    NEWS2 = "NEWS2"
    ML_ANOMALY = "ML_ANOMALY"


def format_instant(moment: datetime, *, millis: bool = False) -> str:
    """Format a timezone-aware datetime as ISO-8601 UTC with a `Z` suffix."""
    if moment.tzinfo is None:
        raise ValueError("datetime must carry a timezone")
    utc = moment.astimezone(timezone.utc)
    if millis:
        return utc.strftime("%Y-%m-%dT%H:%M:%S.") + f"{utc.microsecond // 1000:03d}Z"
    return utc.strftime("%Y-%m-%dT%H:%M:%SZ")


def alert_id(
    patient_id: str, alert_type: AlertType, metric: Metric | None, window_start: datetime
) -> str:
    """UUIDv5 of `patientId|type|metric|windowStart` so replays and re-emits dedupe downstream."""
    metric_part = metric.value if metric else ""
    key = f"{patient_id}|{alert_type.value}|{metric_part}|{format_instant(window_start)}"
    return str(uuid.uuid5(ALERT_NAMESPACE, key))


@dataclass(frozen=True)
class Alert:
    """An AlertEvent ready to serialise."""

    alert_id: str
    patient_id: str
    type: AlertType
    severity: Severity
    metric: Metric | None
    value: float | None
    score: float | None
    message: str
    window_start: datetime
    window_end: datetime
    detected_at: datetime

    def to_json(self) -> str:
        """Serialise with the exact field names of `alert-event.schema.json`."""
        return json.dumps(
            {
                "alertId": self.alert_id,
                "patientId": self.patient_id,
                "type": self.type.value,
                "severity": self.severity.value,
                "metric": self.metric.value if self.metric else None,
                "value": self.value,
                "score": self.score,
                "message": self.message,
                "windowStart": format_instant(self.window_start),
                "windowEnd": format_instant(self.window_end),
                "detectedAt": format_instant(self.detected_at, millis=True),
            },
            separators=(",", ":"),
        )


def _build(
    patient_id: str,
    alert_type: AlertType,
    severity: Severity,
    metric: Metric | None,
    value: float | None,
    score: float | None,
    message: str,
    window_start: datetime,
    window_end: datetime,
    detected_at: datetime,
) -> Alert:
    return Alert(
        alert_id=alert_id(patient_id, alert_type, metric, window_start),
        patient_id=patient_id,
        type=alert_type,
        severity=severity,
        metric=metric,
        value=value,
        score=score,
        message=message,
        window_start=window_start,
        window_end=window_end,
        detected_at=detected_at,
    )


def make_threshold_alert(
    patient_id: str,
    metric: Metric,
    window_avg: float,
    window_start: datetime,
    window_end: datetime,
    detected_at: datetime,
) -> Alert | None:
    """THRESHOLD alert for a window average, or None when it is normal."""
    severity = evaluate_threshold(metric, window_avg)
    if severity is None:
        return None
    message = f"{metric.value} window average {window_avg:.1f} outside normal range"
    return _build(
        patient_id, AlertType.THRESHOLD, severity, metric, window_avg, None, message,
        window_start, window_end, detected_at,
    )  # fmt: skip


def make_trend_alert(
    patient_id: str,
    metric: Metric,
    value: float,
    z: float,
    window_start: datetime,
    window_end: datetime,
    detected_at: datetime,
) -> Alert:
    """TREND_ZSCORE alert (always HIGH per CONTRACTS 3.3)."""
    message = f"{metric.value} {value:.1f} deviates from EWMA trend (z={z:.2f})"
    return _build(
        patient_id, AlertType.TREND_ZSCORE, Severity.HIGH, metric, value, z, message,
        window_start, window_end, detected_at,
    )  # fmt: skip


def make_news2_alert(
    patient_id: str,
    result: News2Result,
    window_start: datetime,
    window_end: datetime,
    detected_at: datetime,
) -> Alert | None:
    """NEWS2 alert for a window, or None when the score is below every alerting rule."""
    severity = news2_severity(result.total, result.max_single)
    if severity is None:
        return None
    detail = ", ".join(result.contributing())
    message = f"NEWS2 score {result.total} ({detail})"
    return _build(
        patient_id, AlertType.NEWS2, severity, None, None, float(result.total), message,
        window_start, window_end, detected_at,
    )  # fmt: skip


def make_anomaly_alert(
    patient_id: str,
    score: float,
    threshold: float,
    window_start: datetime,
    window_end: datetime,
    detected_at: datetime,
) -> Alert:
    """ML_ANOMALY alert (always MEDIUM per CONTRACTS 3.3)."""
    message = f"Anomalous vital-sign pattern: distance {score:.2f} > threshold {threshold:.2f}"
    return _build(
        patient_id, AlertType.ML_ANOMALY, Severity.MEDIUM, None, None, score, message,
        window_start, window_end, detected_at,
    )  # fmt: skip
