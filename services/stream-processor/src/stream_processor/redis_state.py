"""Redis-backed state: EWMA, latest-value hash and the alert emission ledger (CONTRACTS 5)."""

from __future__ import annotations

import json
from collections.abc import Iterable, Mapping
from dataclasses import dataclass
from typing import Any

from stream_processor.alerts import Alert
from stream_processor.ewma import EwmaState
from stream_processor.vitals import Metric

EWMA_TTL_SECONDS = 24 * 3600
LATEST_TTL_SECONDS = 3600
EMITTED_TTL_SECONDS = 15 * 60


def ewma_key(patient_id: str, metric: Metric) -> str:
    """`ewma:{patientId}:{metric}`."""
    return f"ewma:{patient_id}:{metric.value}"


def latest_key(patient_id: str) -> str:
    """`patient:{patientId}:latest`."""
    return f"patient:{patient_id}:latest"


def emitted_key(alert_id: str) -> str:
    """`alert:emitted:{alertId}` ledger entry that stops re-emission of unchanged alerts."""
    return f"alert:emitted:{alert_id}"


class EwmaStore:
    """Load/save EWMA state hashes."""

    def __init__(self, client: Any) -> None:
        self._r = client

    def load_many(self, keys: Iterable[tuple[str, Metric]]) -> dict[tuple[str, Metric], EwmaState]:
        """Fetch states for (patient, metric) pairs in one pipeline; missing keys are omitted."""
        pairs = list(keys)
        pipe = self._r.pipeline(transaction=False)
        for patient_id, metric in pairs:
            pipe.hgetall(ewma_key(patient_id, metric))
        out: dict[tuple[str, Metric], EwmaState] = {}
        for pair, raw in zip(pairs, pipe.execute(), strict=True):
            state = EwmaState.from_hash(raw)
            if state is not None:
                out[pair] = state
        return out

    def save_many(self, states: Mapping[tuple[str, Metric], EwmaState]) -> None:
        """Write states and refresh their 24 h TTL."""
        pipe = self._r.pipeline(transaction=False)
        for (patient_id, metric), state in states.items():
            key = ewma_key(patient_id, metric)
            pipe.hset(key, mapping=state.to_hash())
            pipe.expire(key, EWMA_TTL_SECONDS)
        pipe.execute()


@dataclass(frozen=True)
class LatestReading:
    """Newest reading of one metric for one patient."""

    patient_id: str
    metric: Metric
    value: float
    unit: str
    observed_at_ms: int
    observed_at_iso: str


class LatestStore:
    """Maintain `patient:{id}:latest` hashes without ever moving a metric backwards in time."""

    def __init__(self, client: Any) -> None:
        self._r = client

    def update(self, readings: Iterable[LatestReading]) -> int:
        """Write readings newer than what is stored; return how many were applied."""
        items = list(readings)
        pipe = self._r.pipeline(transaction=False)
        for item in items:
            pipe.hget(latest_key(item.patient_id), item.metric.value)
        current = pipe.execute()
        pipe = self._r.pipeline(transaction=False)
        applied = 0
        touched: set[str] = set()
        for item, existing in zip(items, current, strict=True):
            if existing is not None and _stored_ms(existing) >= item.observed_at_ms:
                continue
            payload = json.dumps(
                {"value": item.value, "unit": item.unit, "observedAt": item.observed_at_iso},
                separators=(",", ":"),
            )
            pipe.hset(latest_key(item.patient_id), item.metric.value, payload)
            touched.add(item.patient_id)
            applied += 1
        for patient_id in touched:
            pipe.expire(latest_key(patient_id), LATEST_TTL_SECONDS)
        pipe.execute()
        return applied


def _stored_ms(raw: str | bytes) -> int:
    from datetime import datetime

    text = raw.decode() if isinstance(raw, bytes) else raw
    observed = json.loads(text)["observedAt"]
    return int(datetime.fromisoformat(observed.replace("Z", "+00:00")).timestamp() * 1000)


class AlertLedger:
    """Remember which alertIds were published, and at what severity.

    alertId is deterministic per window, so the same alert would otherwise be re-published on
    every micro-batch that updates the window. An alert is (re)emitted only if it is new or its
    severity escalated. Callers publish first and `record` afterwards: a crash in between causes a
    duplicate (same alertId, deduplicated downstream), never a lost alert.
    """

    def __init__(self, client: Any) -> None:
        self._r = client

    def filter_new(self, alerts: list[Alert]) -> list[Alert]:
        """Return alerts not yet published at this severity (or higher)."""
        if not alerts:
            return []
        pipe = self._r.pipeline(transaction=False)
        for alert in alerts:
            pipe.get(emitted_key(alert.alert_id))
        fresh: list[Alert] = []
        for alert, seen in zip(alerts, pipe.execute(), strict=True):
            if seen is None or int(seen) < alert.severity.rank:
                fresh.append(alert)
        return fresh

    def record(self, alerts: list[Alert]) -> None:
        """Mark alerts as published."""
        if not alerts:
            return
        pipe = self._r.pipeline(transaction=False)
        for alert in alerts:
            pipe.set(emitted_key(alert.alert_id), alert.severity.rank, ex=EMITTED_TTL_SECONDS)
        pipe.execute()
