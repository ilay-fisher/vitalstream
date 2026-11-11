"""Load mode: paced asynchronous requests with latency percentiles."""

from __future__ import annotations

import asyncio
import math
import time
import uuid
from collections.abc import Awaitable, Callable
from dataclasses import dataclass, field

from vitalstream_sim.catalog import Metric
from vitalstream_sim.client import IngestClient, IngestError, Transport
from vitalstream_sim.generator import VitalsGenerator
from vitalstream_sim.logs import get_logger
from vitalstream_sim.patients import build_patients
from vitalstream_sim.payloads import Reading
from vitalstream_sim.runner import Clock, utc_now
from vitalstream_sim.scenarios import Scenario

log = get_logger()

Sleep = Callable[[float], Awaitable[None]]


@dataclass(frozen=True)
class LoadConfig:
    """Target ``rate`` events/s (readings, not requests) for ``duration_s`` seconds.

    Each request carries ``batch_size`` readings of one patient, so the per-device rate limit
    (50 req/s) is only reached at very high rates. A fixed ``run_id`` makes a rerun resend the
    same event ids, which the gateway must report as duplicates.
    """

    rate: int
    duration_s: float
    batch_size: int = 50
    concurrency: int = 64
    transport: Transport = Transport.BATCH
    seed: int = 42
    run_id: str | None = None


@dataclass
class LoadReport:
    """Result of a load run."""

    sent_events: int = 0
    requests: int = 0
    accepted: int = 0
    duplicates: int = 0
    rejected: int = 0
    failed: int = 0
    latencies: list[float] = field(default_factory=list)
    elapsed_s: float = 0.0

    @property
    def throughput(self) -> float:
        """Events handed to the gateway per second."""
        return self.sent_events / self.elapsed_s if self.elapsed_s > 0 else 0.0

    def render(self) -> str:
        """Human readable summary with p50/p95 latency in milliseconds."""
        p50 = percentile(self.latencies, 50) * 1000
        p95 = percentile(self.latencies, 95) * 1000
        peak = max(self.latencies, default=0.0) * 1000
        return "\n".join(
            [
                f"events sent : {self.sent_events} in {self.elapsed_s:.2f}s "
                f"({self.throughput:.1f} events/s), {self.requests} requests",
                f"latency ms  : p50={p50:.1f} p95={p95:.1f} max={peak:.1f}",
                f"accepted={self.accepted} duplicate={self.duplicates} "
                f"rejected={self.rejected} failed_requests={self.failed}",
            ]
        )


def percentile(values: list[float], pct: float) -> float:
    """Nearest-rank percentile; 0.0 for no data."""
    if not values:
        return 0.0
    ordered = sorted(values)
    rank = max(1, math.ceil(pct / 100.0 * len(ordered)))
    return ordered[min(rank, len(ordered)) - 1]


async def run_load(
    cfg: LoadConfig,
    client: IngestClient,
    *,
    clock: Clock = utc_now,
    monotonic: Callable[[], float] = time.monotonic,
    sleep: Sleep = asyncio.sleep,
) -> LoadReport:
    """Dispatch requests on a fixed schedule; slow responses back-pressure via ``concurrency``."""
    batch = max(1, min(cfg.batch_size, cfg.rate))
    total_requests = max(1, math.ceil(cfg.rate * cfg.duration_s / batch))
    spacing = batch / cfg.rate
    run_id = cfg.run_id or uuid.uuid4().hex
    patients = build_patients(cfg.seed)
    generators = [VitalsGenerator(p, Scenario.STABLE, cfg.seed) for p in patients]
    report = LoadReport()
    gate = asyncio.Semaphore(cfg.concurrency)
    metrics = list(Metric)

    async def one(index: int, readings: list[Reading]) -> None:
        try:
            result = await client.send(cfg.transport, readings)
        except IngestError:
            report.failed += 1
        else:
            report.accepted += result.accepted
            report.duplicates += result.duplicates
            report.rejected += len(result.rejected)
            report.latencies.append(result.latency_s)
        finally:
            gate.release()

    def build(index: int) -> list[Reading]:
        generator = generators[index % len(generators)]
        now = clock()
        values = generator.snapshot(index * spacing, index, now)
        return [
            Reading(
                generator.patient.id,
                generator.patient.device_id,
                metrics[j % len(metrics)],
                values[metrics[j % len(metrics)]],
                now,
                id_seed=f"{run_id}|{index * batch + j}",
            )
            for j in range(batch)
        ]

    log.info("load.started", rate=cfg.rate, durationSeconds=cfg.duration_s, batchSize=batch)
    started = monotonic()
    tasks: list[asyncio.Task[None]] = []
    for index in range(total_requests):
        delay = started + index * spacing - monotonic()
        if delay > 0:
            await sleep(delay)
        await gate.acquire()
        report.requests += 1
        report.sent_events += batch
        tasks.append(asyncio.create_task(one(index, build(index))))
    await asyncio.gather(*tasks)
    report.elapsed_s = monotonic() - started
    log.info("load.finished", events=report.sent_events, requests=report.requests)
    return report
