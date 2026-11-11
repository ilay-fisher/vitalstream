from __future__ import annotations

import uuid
from datetime import timedelta

from tests.conftest import T0
from vitalstream_sim.catalog import Metric
from vitalstream_sim.ids import event_id, format_hl7_ts, format_iso


def test_event_id_is_stable_uuid5() -> None:
    first = event_id("P-0001", Metric.HEART_RATE, T0)
    assert first == event_id("P-0001", Metric.HEART_RATE, T0)
    assert uuid.UUID(first).version == 5


def test_event_id_changes_with_each_component() -> None:
    base = event_id("P-0001", Metric.HEART_RATE, T0)
    assert base != event_id("P-0002", Metric.HEART_RATE, T0)
    assert base != event_id("P-0001", Metric.SPO2, T0)
    assert base != event_id("P-0001", Metric.HEART_RATE, T0 + timedelta(milliseconds=1))


def test_timestamp_formats() -> None:
    assert format_iso(T0) == "2026-11-11T20:15:30.123Z"
    assert format_hl7_ts(T0) == "20261111201530.123+0000"
