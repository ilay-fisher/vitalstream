from datetime import datetime, timezone

import fakeredis
import pytest

from stream_processor.alerts import make_threshold_alert
from stream_processor.ewma import EwmaState
from stream_processor.redis_state import (
    AlertLedger,
    EwmaStore,
    LatestReading,
    LatestStore,
    ewma_key,
    latest_key,
)
from stream_processor.vitals import Metric

WS = datetime(2026, 11, 11, 20, 15, tzinfo=timezone.utc)
WE = datetime(2026, 11, 11, 20, 16, tzinfo=timezone.utc)


@pytest.fixture
def client() -> fakeredis.FakeRedis:
    return fakeredis.FakeRedis(decode_responses=True)


def test_key_names_follow_contract() -> None:
    assert ewma_key("P-0001", Metric.HEART_RATE) == "ewma:P-0001:HEART_RATE"
    assert latest_key("P-0001") == "patient:P-0001:latest"


def test_ewma_roundtrip_with_ttl(client: fakeredis.FakeRedis) -> None:
    store = EwmaStore(client)
    state = EwmaState(mean=70.0, var=2.0, n=11, last_ts=5, hist="+.+")
    store.save_many({("P-0001", Metric.HEART_RATE): state})
    assert client.hgetall("ewma:P-0001:HEART_RATE")["n"] == "11"
    assert client.hget("ewma:P-0001:HEART_RATE", "hist") == "+.+"
    assert 0 < client.ttl("ewma:P-0001:HEART_RATE") <= 24 * 3600
    loaded = store.load_many([("P-0001", Metric.HEART_RATE), ("P-0001", Metric.SPO2)])
    assert loaded == {("P-0001", Metric.HEART_RATE): state}


def test_v1_ewma_hash_without_hist_is_treated_as_missing(client: fakeredis.FakeRedis) -> None:
    client.hset(
        "ewma:P-0001:HEART_RATE", mapping={"mean": "72.0", "var": "3.0", "n": "500", "ts": "99"}
    )
    assert EwmaStore(client).load_many([("P-0001", Metric.HEART_RATE)]) == {}


def _reading(ms: int, value: float, iso: str) -> LatestReading:
    return LatestReading("P-0001", Metric.HEART_RATE, value, "/min", ms, iso)


def test_latest_store_writes_json_and_ttl(client: fakeredis.FakeRedis) -> None:
    applied = LatestStore(client).update([_reading(1000, 80.0, "2026-11-11T20:15:01.000Z")])
    assert applied == 1
    raw = client.hget("patient:P-0001:latest", "HEART_RATE")
    assert raw == '{"value":80.0,"unit":"/min","observedAt":"2026-11-11T20:15:01.000Z"}'
    assert client.ttl("patient:P-0001:latest") > 0


def test_latest_store_never_moves_backwards(client: fakeredis.FakeRedis) -> None:
    store = LatestStore(client)
    newer = int(datetime(2026, 11, 11, 20, 15, 10, tzinfo=timezone.utc).timestamp() * 1000)
    store.update([_reading(newer, 90.0, "2026-11-11T20:15:10.000Z")])
    applied = store.update(
        [
            _reading(newer - 5000, 70.0, "2026-11-11T20:15:05.000Z"),
            _reading(newer, 91.0, "2026-11-11T20:15:10.000Z"),
        ]
    )
    assert applied == 0
    assert '"value":90.0' in client.hget("patient:P-0001:latest", "HEART_RATE")


def test_ledger_filters_replays_but_lets_escalations_through(client: fakeredis.FakeRedis) -> None:
    ledger = AlertLedger(client)
    medium = make_threshold_alert("P-0001", Metric.SPO2, 94.0, WS, WE, WE)
    critical = make_threshold_alert("P-0001", Metric.SPO2, 85.0, WS, WE, WE)
    assert medium and critical and medium.alert_id == critical.alert_id
    assert ledger.filter_new([medium]) == [medium]
    ledger.record([medium])
    assert ledger.filter_new([medium]) == []
    assert ledger.filter_new([critical]) == [critical]
    ledger.record([critical])
    assert ledger.filter_new([medium, critical]) == []


def test_ledger_empty_inputs(client: fakeredis.FakeRedis) -> None:
    ledger = AlertLedger(client)
    assert ledger.filter_new([]) == []
    ledger.record([])
