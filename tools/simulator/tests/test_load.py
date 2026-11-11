from __future__ import annotations

import json
from datetime import timedelta

import httpx
import respx

from tests.test_runner import FakeClock
from vitalstream_sim.client import IngestClient
from vitalstream_sim.load import LoadConfig, LoadReport, percentile, run_load

BASE = "http://gw.test:8081"


class FakeTime:
    """Virtual monotonic clock advanced by the load generator's sleeps."""

    def __init__(self) -> None:
        self.t = 0.0

    def monotonic(self) -> float:
        return self.t

    async def sleep(self, delay: float) -> None:
        self.t += max(delay, 0.0)


def test_percentile_nearest_rank() -> None:
    data = [float(i) for i in range(1, 101)]
    assert percentile(data, 50) == 50.0
    assert percentile(data, 95) == 95.0
    assert percentile([], 50) == 0.0
    assert percentile([7.0], 95) == 7.0


@respx.mock
async def test_load_reaches_requested_rate_and_counts() -> None:
    seen_ids: set[str] = set()

    def handler(request: httpx.Request) -> httpx.Response:
        body = json.loads(request.content)
        ids = {r["eventId"] for r in body["readings"]}
        dups = len(ids & seen_ids)
        seen_ids.update(ids)
        return httpx.Response(
            202, json={"accepted": len(ids) - dups, "duplicates": dups, "rejected": []}
        )

    route = respx.post(f"{BASE}/api/v1/vitals/batch").mock(side_effect=handler)
    fake = FakeTime()
    client = IngestClient(BASE, "k", sleep=fake.sleep)
    cfg = LoadConfig(rate=200, duration_s=5.0, batch_size=50, run_id="test-run")
    report = await run_load(
        cfg, client, clock=FakeClock(), monotonic=fake.monotonic, sleep=fake.sleep
    )
    assert report.sent_events == 1000
    assert route.call_count == 20
    assert report.accepted == 1000
    assert report.duplicates == 0
    assert report.rejected == 0
    assert report.failed == 0
    assert len(report.latencies) == 20
    assert 4.5 <= report.elapsed_s <= 5.5
    assert 180 <= report.throughput <= 220
    for line in ("p50", "p95", "accepted=1000", "duplicate=0", "rejected=0"):
        assert line in report.render()
    await client.aclose()


@respx.mock
async def test_load_event_ids_are_unique_within_run_and_repeat_with_same_run_id() -> None:
    route = respx.post(f"{BASE}/api/v1/vitals/batch").respond(
        202, json={"accepted": 1, "duplicates": 0, "rejected": []}
    )
    fake = FakeTime()
    client = IngestClient(BASE, "k", sleep=fake.sleep)
    cfg = LoadConfig(rate=60, duration_s=2.0, batch_size=30, run_id="same")

    async def ids() -> list[str]:
        route.reset()
        clock = FakeClock()
        await run_load(cfg, client, clock=clock, monotonic=fake.monotonic, sleep=fake.sleep)
        out: list[str] = []
        for call in route.calls:
            out += [r["eventId"] for r in json.loads(call.request.content)["readings"]]
        return out

    first = await ids()
    second = await ids()
    assert len(first) == len(set(first)) == 120
    assert sorted(first) == sorted(second)
    await client.aclose()


@respx.mock
async def test_load_counts_rejections_and_failures() -> None:
    calls = {"n": 0}

    def handler(_: httpx.Request) -> httpx.Response:
        calls["n"] += 1
        if calls["n"] % 2 == 0:
            return httpx.Response(401, json={"title": "no"})
        return httpx.Response(
            202,
            json={
                "accepted": 8,
                "duplicates": 1,
                "rejected": [{"eventId": "x", "reason": "OUT_OF_RANGE"}],
            },
        )

    respx.post(f"{BASE}/api/v1/vitals/batch").mock(side_effect=handler)
    fake = FakeTime()
    client = IngestClient(BASE, "k", sleep=fake.sleep)
    cfg = LoadConfig(rate=40, duration_s=1.0, batch_size=10)
    report = await run_load(
        cfg, client, clock=FakeClock(), monotonic=fake.monotonic, sleep=fake.sleep
    )
    assert report.requests == 4
    assert report.failed == 2
    assert (report.accepted, report.duplicates, report.rejected) == (16, 2, 2)


def test_report_with_no_data_renders() -> None:
    report = LoadReport()
    assert "p50" in report.render()
    assert report.throughput == 0.0
    _ = timedelta
