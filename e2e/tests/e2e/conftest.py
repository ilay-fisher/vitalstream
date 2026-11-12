"""Session fixtures: stack lifecycle, service URLs and the long-running pipeline scenario.

The sepsis scenario is started once; individual tests then assert one hop of the pipeline each,
so a failure points at the broken hop instead of at "the whole thing".
"""

from __future__ import annotations

import os
import sys
import time
import uuid
from collections.abc import Iterator
from dataclasses import dataclass, field
from datetime import UTC, datetime
from pathlib import Path

import httpx
import pytest

from vitalstream_e2e.kafka_tail import KafkaTail, tail_topic
from vitalstream_e2e.polling import poll_until
from vitalstream_e2e.probes import batch_body
from vitalstream_e2e.sse import SseCollector, parse_sse
from vitalstream_e2e.stack import (
    COMPONENTS,
    PROJECT_NAME,
    TeardownMode,
    compose_command,
    container_running,
    resolve_teardown,
    run,
    simulator_command,
)

REPO = Path(__file__).resolve().parents[3]
PATIENT = "P-0003"
GATEWAY = os.environ.get("INGEST_GATEWAY_URL", "http://localhost:8081")
CLINICAL_API = os.environ.get("CLINICAL_API_URL", "http://localhost:8082")
COPILOT = os.environ.get("COPILOT_URL", "http://localhost:8083")
KAFKA = os.environ.get("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092")
API_KEY = os.environ.get("INGEST_API_KEY", "dev-ingest-key")
SIM_DURATION = int(os.environ.get("E2E_SIM_DURATION", "150"))
SIM_SPEED = os.environ.get("E2E_SIM_SPEED", "20x")


def log(message: str) -> None:
    print(f"[e2e {time.strftime('%H:%M:%S')}] {message}", file=sys.stderr, flush=True)


def stack_env() -> dict[str, str]:
    """Same compose project as `make up` in a checkout called `vitalstream`."""
    return {**os.environ, "COMPOSE_PROJECT_NAME": PROJECT_NAME}


@dataclass
class Pipeline:
    """Everything the scenario produced, shared by the per-hop tests."""

    probe_event_id: str = field(default_factory=lambda: str(uuid.uuid4()))
    probe_response: httpx.Response | None = None
    vitals_tail: KafkaTail | None = None
    alerts_tail: KafkaTail | None = None
    sse: SseCollector | None = None
    started_at: datetime = field(default_factory=lambda: datetime.now(UTC))
    simulator_output: str = ""


@pytest.fixture(scope="session")
def stack() -> Iterator[None]:
    """Start infra and every component through the same compose files as `make up`."""
    env = stack_env()
    infra_was_running = container_running("vitalstream-kafka")
    mode = resolve_teardown(os.environ.get("E2E_TEARDOWN"), infra_was_running=infra_was_running)
    log(f"infra already running: {infra_was_running}; teardown mode: {mode.value}")
    run(["make", "-C", str(REPO), "infra-up"], env=env)
    build = ["--no-build"] if os.environ.get("E2E_SKIP_BUILD") else ["--build"]
    run(
        compose_command(REPO, "up", "-d", *build, "--wait", "--wait-timeout", "600", *COMPONENTS),
        env=env,
    )
    try:
        yield
    finally:
        _teardown(mode, env)


def _teardown(mode: TeardownMode, env: dict[str, str]) -> None:
    if mode is TeardownMode.NONE:
        log("teardown skipped (E2E_TEARDOWN=none)")
        return
    if mode is TeardownMode.ALL:
        log("tearing down the whole stack")
        run(
            compose_command(REPO, "--profile", "tools", "down", "--remove-orphans"),
            env=env,
            check=False,
        )
    else:
        log("stopping components only; pre-existing infra stays up")
        run(compose_command(REPO, "rm", "-sf", *COMPONENTS), env=env, check=False)


@pytest.fixture(scope="session")
def pipeline(stack: None) -> Iterator[Pipeline]:
    """Start the taps, send the gateway probe, then run the sepsis scenario for P-0003."""
    state = Pipeline()
    state.vitals_tail = tail_topic(KAFKA, "vitals.raw")
    state.alerts_tail = tail_topic(KAFKA, "alerts.detected")
    state.sse = SseCollector(_alert_stream)
    state.sse.start()

    log("probe: POST one reading to the gateway")
    state.probe_response = httpx.post(
        f"{GATEWAY}/api/v1/vitals/batch",
        headers={"X-Api-Key": API_KEY, "X-Correlation-Id": f"e2e-{state.probe_event_id}"},
        json=batch_body(
            PATIENT, "DEV-ICU-0003", state.probe_event_id, "HEART_RATE", 80.0, datetime.now(UTC)
        ),
        timeout=15,
    )

    log(f"simulator: sepsis scenario for {PATIENT} ({SIM_DURATION}s at {SIM_SPEED})")
    state.simulator_output = run(
        simulator_command(
            REPO, patient=PATIENT, scenario="sepsis", duration=SIM_DURATION, speed=SIM_SPEED
        ),
        env=stack_env(),
        check=False,
    )
    try:
        yield state
    finally:
        for tail in (state.vitals_tail, state.alerts_tail):
            tail.stop()
        state.sse.stop()


def _alert_stream():  # type: ignore[no-untyped-def]
    """Open the clinical-api live feed (no replay: must be listening before alerts are raised)."""
    with httpx.stream(
        "GET",
        f"{CLINICAL_API}/api/v1/alerts/stream",
        headers={"Accept": "text/event-stream"},
        timeout=httpx.Timeout(10, read=None),
    ) as response:
        response.raise_for_status()
        yield from parse_sse(response.iter_lines())


@pytest.fixture(scope="session")
def alerts_for_patient(pipeline: Pipeline) -> list[dict[str, object]]:
    """Alerts of P-0003 from the REST API once at least one exists."""

    def fetch() -> list[dict[str, object]]:
        page = httpx.get(
            f"{CLINICAL_API}/api/v1/alerts", params={"patientId": PATIENT, "size": 50}, timeout=10
        ).json()
        return list(page["items"])

    return poll_until(
        fetch,
        timeout=180,
        interval=3,
        description=f"clinical-api alerts for {PATIENT}",
        retry_on=(httpx.HTTPError, KeyError, ValueError),
    )
