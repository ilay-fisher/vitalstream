import json
from datetime import UTC, datetime

from vitalstream_e2e.kafka_tail import KafkaTail, Record
from vitalstream_e2e.probes import ack_body, batch_body, find_open_alert, tool_calls


def test_batch_body_matches_gateway_contract():
    now = datetime(2026, 11, 12, 10, 0, 0, tzinfo=UTC)

    body = batch_body("P-0003", "DEV-E2E-0003", "e1", "HEART_RATE", 88.0, now)

    assert body == {
        "deviceId": "DEV-E2E-0003",
        "patientId": "P-0003",
        "readings": [
            {
                "eventId": "e1",
                "metric": "HEART_RATE",
                "value": 88.0,
                "observedAt": "2026-11-12T10:00:00.000Z",
            }
        ],
    }


def test_ack_body_names_actor_and_note():
    assert ack_body("e2e")["by"] == "e2e"
    assert ack_body("e2e")["note"]


def test_find_open_alert_picks_open_alert():
    page = {
        "items": [
            {"alertId": "a", "status": "ACKNOWLEDGED"},
            {"alertId": "b", "status": "OPEN"},
        ]
    }

    assert find_open_alert(page)["alertId"] == "b"
    assert find_open_alert({"items": []}) is None


def test_tool_calls_keeps_completed_calls_only():
    events = [
        ("tool_call", {"name": "list_alerts", "status": "running"}),
        ("tool_call", {"name": "list_alerts", "status": "ok"}),
        ("token", {"text": "x"}),
        ("tool_call", {"name": "get_patient_vitals", "status": "error"}),
    ]

    assert tool_calls(events) == ["list_alerts"]


class FakeMessage:
    def __init__(self, value, key=None, error=None, headers=None):
        self._value, self._key, self._error, self._headers = value, key, error, headers

    def value(self):
        return self._value

    def key(self):
        return self._key

    def error(self):
        return self._error

    def headers(self):
        return self._headers


class FakeConsumer:
    def __init__(self, messages):
        self.messages = list(messages)
        self.closed = False

    def poll(self, timeout):
        return self.messages.pop(0) if self.messages else None

    def close(self):
        self.closed = True


def test_tail_decodes_json_skips_bad_records_and_filters_by_predicate():
    msgs = [
        FakeMessage(json.dumps({"patientId": "P-0001"}).encode(), b"P-0001"),
        FakeMessage(b"not json", b"P-0003"),
        FakeMessage(None),
        FakeMessage(b"{}", error="boom"),
        FakeMessage(
            json.dumps({"patientId": "P-0003", "eventId": "e1"}).encode(),
            b"P-0003",
            headers=[("correlationId", b"c-1")],
        ),
    ]
    consumer = FakeConsumer(msgs)
    tail = KafkaTail(consumer, topic="vitals.raw")
    tail.start()

    record = tail.wait_for(
        lambda r: r.value.get("patientId") == "P-0003", timeout=2, description="P-0003 record"
    )
    tail.stop()

    assert isinstance(record, Record)
    assert record.key == "P-0003"
    assert record.headers == {"correlationId": "c-1"}
    assert record.value["eventId"] == "e1"
    assert consumer.closed
