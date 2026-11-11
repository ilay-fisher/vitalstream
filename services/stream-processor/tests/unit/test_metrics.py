import json
import urllib.error
import urllib.request

import pytest

from stream_processor.metrics import Metrics, start_http_server


def test_counters_render_with_labels() -> None:
    m = Metrics()
    m.alerts_raised.labels(type="NEWS2", severity="HIGH").inc()
    m.alerts_raised.labels(type="NEWS2", severity="HIGH").inc()
    m.malformed_events.inc(3)
    text = m.render().decode()
    assert 'vitalstream_alerts_raised_total{severity="HIGH",type="NEWS2"} 2.0' in text
    assert "vitalstream_malformed_events_total 3.0" in text


def test_http_endpoints() -> None:
    m = Metrics()
    m.events_processed.inc(5)
    state = {"status": "UP"}
    server = start_http_server(0, m, lambda: state)
    base = f"http://127.0.0.1:{server.server_address[1]}"
    try:
        body = urllib.request.urlopen(f"{base}/metrics").read().decode()
        assert "vitalstream_events_processed_total 5.0" in body
        assert json.loads(urllib.request.urlopen(f"{base}/health").read()) == {"status": "UP"}
        state["status"] = "DOWN"
        with pytest.raises(urllib.error.HTTPError) as down:
            urllib.request.urlopen(f"{base}/health")
        assert down.value.code == 503
        with pytest.raises(urllib.error.HTTPError) as missing:
            urllib.request.urlopen(f"{base}/nope")
        assert missing.value.code == 404
    finally:
        server.shutdown()
        server.server_close()
