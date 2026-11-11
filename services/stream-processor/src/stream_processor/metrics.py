"""Business metrics (Prometheus) and a tiny HTTP endpoint serving `/metrics` and `/health`.

Spark's own metrics are exposed by its PrometheusServlet on the UI port 4040
(`/metrics/prometheus`); the counters below are Python-side business counters.
"""

from __future__ import annotations

import json
import threading
from collections.abc import Callable
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any

from prometheus_client import CollectorRegistry, Counter, generate_latest
from prometheus_client.exposition import CONTENT_TYPE_LATEST


class Metrics:
    """Counters owned by one registry so tests can create isolated instances."""

    def __init__(self, registry: CollectorRegistry | None = None) -> None:
        self.registry = registry or CollectorRegistry()
        self.alerts_raised = Counter(
            "vitalstream_alerts_raised_total",
            "Alerts published to alerts.detected",
            ["type", "severity"],
            registry=self.registry,
        )
        self.events_processed = Counter(
            "vitalstream_events_processed_total",
            "Valid vital-sign events processed",
            registry=self.registry,
        )
        self.malformed_events = Counter(
            "vitalstream_malformed_events_total",
            "Kafka records dropped as malformed",
            registry=self.registry,
        )
        self.events_dropped = Counter(
            "vitalstream_events_dropped_total",
            "Events rejected by the event-time guard",
            ["reason"],
            registry=self.registry,
        )
        self.batches = Counter(
            "vitalstream_batches_processed_total",
            "Micro-batches processed",
            ["query"],
            registry=self.registry,
        )

    def render(self) -> bytes:
        """Prometheus text exposition."""
        return generate_latest(self.registry)


def start_http_server(
    port: int, metrics: Metrics, health: Callable[[], dict[str, Any]]
) -> ThreadingHTTPServer:
    """Serve `/metrics` and `/health` on a daemon thread (port 0 picks an ephemeral port)."""

    class Handler(BaseHTTPRequestHandler):
        def do_GET(self) -> None:
            if self.path.startswith("/metrics"):
                body, ctype, code = metrics.render(), CONTENT_TYPE_LATEST, 200
            elif self.path.startswith("/health"):
                status = health()
                body = json.dumps(status).encode()
                ctype, code = "application/json", 200 if status.get("status") == "UP" else 503
            else:
                body, ctype, code = b"not found", "text/plain", 404
            self.send_response(code)
            self.send_header("Content-Type", ctype)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, format: str, *args: Any) -> None:
            return

    server = ThreadingHTTPServer(("0.0.0.0", port), Handler)
    threading.Thread(target=server.serve_forever, daemon=True, name="metrics-http").start()
    return server
