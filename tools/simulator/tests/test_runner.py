from __future__ import annotations

import json
from datetime import UTC, datetime, timedelta

import httpx
import respx

from tests.conftest import T0
from vitalstream_sim.client import IngestClient, Transport
from vitalstream_sim.patients import build_patients
from vitalstream_sim.runner import RunConfig, assign_scenarios, run_simulation
from vitalstream_sim.scenarios import Scenario

BASE = "http://gw.test:8081"


async def no_sleep(_: float) -> None:
    return None


class FakeClock:
    def __init__(self) -> None:
        self.now = T0

    def __call__(self) -> datetime:
        current = self.now
        self.now += timedelta(seconds=1)
        return current


def responder(request: httpx.Request) -> httpx.Response:
    body = json.loads(request.content)
    n = len(body["readings"])
    return httpx.Response(202, json={"accepted": n, "duplicates": 0, "rejected": []})


def test_assign_scenarios_default_affected_and_overrides() -> None:
    patients = build_patients(count=5)
    cfg = RunConfig(
        default_scenario=Scenario.SEPSIS, affected=2, assignments={"P-0005": Scenario.HYPOXIA}
    )
    got = assign_scenarios(patients, cfg)
    assert got == {
        "P-0001": Scenario.SEPSIS,
        "P-0002": Scenario.SEPSIS,
        "P-0003": Scenario.STABLE,
        "P-0004": Scenario.STABLE,
        "P-0005": Scenario.HYPOXIA,
    }


def test_assign_scenarios_all_affected_by_default() -> None:
    got = assign_scenarios(build_patients(count=3), RunConfig(default_scenario=Scenario.SEPSIS))
    assert set(got.values()) == {Scenario.SEPSIS}


@respx.mock
async def test_run_sends_one_request_per_patient_per_tick() -> None:
    route = respx.post(f"{BASE}/api/v1/vitals/batch").mock(side_effect=responder)
    client = IngestClient(BASE, "k", sleep=no_sleep)
    cfg = RunConfig(patients=3, max_ticks=4, interval_s=0.0, speed=10.0)
    stats = await run_simulation(cfg, client, clock=FakeClock(), sleep=no_sleep)
    assert route.call_count == 12
    assert stats.requests == 12
    assert stats.accepted == 12 * 6
    assert stats.failed == 0
    devices = {json.loads(c.request.content)["deviceId"] for c in route.calls}
    assert devices == {"DEV-ICU-0001", "DEV-ICU-0002", "DEV-ICU-0003"}
    await client.aclose()


@respx.mock
async def test_run_scenario_progresses_with_speed() -> None:
    route = respx.post(f"{BASE}/api/v1/vitals/batch").mock(side_effect=responder)
    client = IngestClient(BASE, "k", sleep=no_sleep)
    cfg = RunConfig(
        patients=1, default_scenario=Scenario.SEPSIS, max_ticks=40, interval_s=1.0, speed=20.0
    )
    await run_simulation(cfg, client, clock=FakeClock(), sleep=no_sleep)

    def hr(call_index: int) -> float:
        body = json.loads(route.calls[call_index].request.content)
        return next(r["value"] for r in body["readings"] if r["metric"] == "HEART_RATE")

    assert hr(39) > hr(0) + 25
    await client.aclose()


@respx.mock
async def test_run_counts_failures_and_continues() -> None:
    respx.post(f"{BASE}/api/v1/vitals/batch").respond(400, json={"title": "bad"})
    client = IngestClient(BASE, "k", sleep=no_sleep)
    cfg = RunConfig(patients=2, max_ticks=2, interval_s=0.0)
    stats = await run_simulation(cfg, client, clock=FakeClock(), sleep=no_sleep)
    assert stats.failed == 4
    assert stats.accepted == 0
    await client.aclose()


@respx.mock
async def test_run_uses_fhir_transport_and_stops_after_duration() -> None:
    route = respx.post(f"{BASE}/api/v1/fhir/Observation").respond(
        202, json={"accepted": 6, "duplicates": 0, "rejected": []}
    )
    client = IngestClient(BASE, "k", sleep=no_sleep)
    cfg = RunConfig(patients=1, duration_s=3.0, interval_s=1.0, transport=Transport.FHIR)
    stats = await run_simulation(cfg, client, clock=FakeClock(), sleep=no_sleep)
    assert route.call_count == 3
    assert stats.requests == 3
    await client.aclose()


@respx.mock
async def test_run_is_idempotent_for_identical_clock() -> None:
    route = respx.post(f"{BASE}/api/v1/vitals/batch").mock(side_effect=responder)
    client = IngestClient(BASE, "k", sleep=no_sleep)
    cfg = RunConfig(patients=1, max_ticks=1, interval_s=0.0)
    fixed = lambda: datetime(2026, 1, 1, tzinfo=UTC)  # noqa: E731
    await run_simulation(cfg, client, clock=fixed, sleep=no_sleep)
    await run_simulation(cfg, client, clock=fixed, sleep=no_sleep)
    assert route.calls[0].request.content == route.calls[1].request.content
    await client.aclose()
