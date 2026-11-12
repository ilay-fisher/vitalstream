import json

import pytest

from vitalstream_e2e.polling import PollTimeout
from vitalstream_e2e.sse import SseCollector, SseEvent, parse_sse


def test_parses_event_id_and_json_data():
    lines = ["event: alert", "id: abc:0", 'data: {"alertId": "abc"}', ""]

    events = list(parse_sse(lines))

    assert events == [SseEvent(event="alert", id="abc:0", data='{"alertId": "abc"}')]
    assert events[0].json() == {"alertId": "abc"}


def test_multiline_data_is_joined_and_comments_ignored():
    lines = [": keep-alive", "event: token", "data: hello", "data: world", ""]

    (event,) = parse_sse(lines)

    assert event.data == "hello\nworld"
    assert event.id is None


def test_default_event_name_and_unterminated_event_dropped():
    lines = ["data: one", "", "event: x", "data: unfinished"]

    events = list(parse_sse(lines))

    assert [e.event for e in events] == ["message"]


def test_retry_and_unknown_fields_are_ignored():
    lines = ["retry: 3000", "foo: bar", "event: e", "data: {}", ""]

    (event,) = parse_sse(lines)

    assert event.event == "e"
    assert json.loads(event.data) == {}


def test_collector_waits_for_matching_event():
    collector = SseCollector(lambda: iter([]))
    collector.add(SseEvent("alert", "1:0", '{"patientId": "P-0001"}'))
    collector.add(SseEvent("alert", "2:0", '{"patientId": "P-0003"}'))

    found = collector.wait_for(
        lambda e: e.json()["patientId"] == "P-0003", timeout=1, description="P-0003 alert"
    )

    assert found.id == "2:0"


def test_collector_runs_stream_in_background_thread():
    def stream():
        yield from parse_sse(["event: alert", 'data: {"a": 1}', ""])

    collector = SseCollector(stream)
    collector.start()
    found = collector.wait_for(lambda e: e.event == "alert", timeout=5, description="alert")
    collector.stop()

    assert found.json() == {"a": 1}
    assert collector.error is None


def test_collector_surfaces_stream_error_in_timeout_message():
    def stream():
        raise ConnectionError("stream refused")
        yield  # pragma: no cover

    collector = SseCollector(stream)
    collector.start()

    with pytest.raises(PollTimeout) as excinfo:
        collector.wait_for(lambda e: True, timeout=0.3, description="never", interval=0.05)

    assert "stream refused" in str(excinfo.value)
