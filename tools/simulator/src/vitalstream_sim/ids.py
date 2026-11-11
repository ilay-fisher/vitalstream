"""Deterministic identifiers and wire timestamp formats."""

from __future__ import annotations

import uuid
from datetime import UTC, datetime

from vitalstream_sim.catalog import Metric

NAMESPACE = uuid.uuid5(uuid.NAMESPACE_DNS, "simulator.vitalstream.local")


def event_id(patient_id: str, metric: Metric, observed_at: datetime) -> str:
    """UUIDv5 of patient, metric and observation instant.

    The same reading always maps to the same id, so a retry (or a replayed run with a fixed
    clock) is recognised by the gateway's idempotency check instead of being stored twice.
    """
    return str(uuid.uuid5(NAMESPACE, f"{patient_id}|{metric}|{format_iso(observed_at)}"))


def seeded_id(seed: str) -> str:
    """UUIDv5 from an arbitrary seed string (used by load mode: run id + sequence number)."""
    return str(uuid.uuid5(NAMESPACE, seed))


def format_iso(moment: datetime) -> str:
    """ISO-8601 UTC with millisecond precision and a ``Z`` suffix, as the schemas require."""
    utc = moment.astimezone(UTC)
    return f"{utc:%Y-%m-%dT%H:%M:%S}.{utc.microsecond // 1000:03d}Z"


def format_hl7_ts(moment: datetime) -> str:
    """HL7 v2 TS value (YYYYMMDDHHMMSS.SSS+0000)."""
    utc = moment.astimezone(UTC)
    return f"{utc:%Y%m%d%H%M%S}.{utc.microsecond // 1000:03d}+0000"
