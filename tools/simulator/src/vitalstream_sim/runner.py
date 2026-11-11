"""Scenario run mode: a fleet of simulated patients streaming readings to the gateway."""

from __future__ import annotations

import asyncio
import math
from collections.abc import Awaitable, Callable, Mapping
from dataclasses import dataclass, field
from datetime import UTC, datetime

from vitalstream_sim.client import IngestClient, IngestError, SendResult, Transport
from vitalstream_sim.generator import VitalsGenerator
from vitalstream_sim.logs import get_logger
from vitalstream_sim.patients import Patient, build_patients
from vitalstream_sim.payloads import Reading, chunk
from vitalstream_sim.scenarios import Scenario

log = get_logger()

Clock = Callable[[], datetime]
Sleep = Callable[[float], Awaitable[None]]


def utc_now() -> datetime:
    """Wall clock, truncated to milliseconds (the wire precision)."""
    now = datetime.now(UTC)
    return now.replace(microsecond=now.microsecond // 1000 * 1000)


@dataclass(frozen=True)
class RunConfig:
    """What to simulate.

    ``speed`` multiplies how fast the scenario unfolds relative to wall time (a 10 minute sepsis
    ramp at 10x takes one minute). Observation timestamps always follow the wall clock, because
    the gateway rejects timestamps from the future.
    """

    patients: int = 5
    default_scenario: Scenario = Scenario.STABLE
    affected: int | None = None
    assignments: Mapping[str, Scenario] = field(default_factory=dict)
    speed: float = 1.0
    interval_s: float = 1.0
    duration_s: float | None = None
    max_ticks: int | None = None
    transport: Transport = Transport.BATCH
    seed: int = 42

    def total_ticks(self) -> int | None:
        """Number of sampling rounds, or None to run until interrupted."""
        limits: list[int] = []
        if self.max_ticks is not None:
            limits.append(self.max_ticks)
        if self.duration_s is not None and self.interval_s > 0:
            limits.append(max(1, math.ceil(self.duration_s / self.interval_s)))
        return min(limits) if limits else None


@dataclass
class RunStats:
    """Running totals reported at the end of a run."""

    requests: int = 0
    accepted: int = 0
    duplicates: int = 0
    rejected: int = 0
    failed: int = 0

    def add(self, result: SendResult) -> None:
        """Fold one successful response into the totals."""
        self.requests += 1
        self.accepted += result.accepted
        self.duplicates += result.duplicates
        self.rejected += len(result.rejected)

    def render(self) -> str:
        """One-line summary."""
        return (
            f"requests={self.requests} accepted={self.accepted} duplicate={self.duplicates} "
            f"rejected={self.rejected} failed={self.failed}"
        )


def assign_scenarios(patients: list[Patient], cfg: RunConfig) -> dict[str, Scenario]:
    """Scenario per patient: the first ``affected`` get ``default_scenario``, the rest are stable,
    and explicit ``assignments`` win."""
    affected = len(patients) if cfg.affected is None else cfg.affected
    chosen = {
        p.id: cfg.default_scenario if index < affected else Scenario.STABLE
        for index, p in enumerate(patients)
    }
    chosen.update({pid: s for pid, s in cfg.assignments.items() if pid in chosen})
    return chosen


async def _deliver(
    client: IngestClient, transport: Transport, readings: list[Reading], stats: RunStats
) -> None:
    for part in chunk(readings):
        try:
            stats.add(await client.send(transport, part))
        except IngestError as error:
            stats.requests += 1
            stats.failed += 1
            log.error("run.send_failed", status=error.status, detail=error.detail)


async def run_simulation(
    cfg: RunConfig,
    client: IngestClient,
    *,
    clock: Clock = utc_now,
    sleep: Sleep = asyncio.sleep,
    stats: RunStats | None = None,
) -> RunStats:
    """Stream readings until the configured number of ticks (or forever)."""
    stats = stats if stats is not None else RunStats()
    patients = build_patients(cfg.seed, cfg.patients)
    scenarios = assign_scenarios(patients, cfg)
    generators = {p.id: VitalsGenerator(p, scenarios[p.id], cfg.seed) for p in patients}
    total = cfg.total_ticks()
    log.info(
        "run.started", patients=len(patients), transport=cfg.transport.value, speed=cfg.speed,
        scenarios={s.value: list(scenarios.values()).count(s) for s in set(scenarios.values())},
    )  # fmt: skip

    tick = 0
    while total is None or tick < total:
        at = clock()
        scenario_t = tick * cfg.interval_s * cfg.speed
        batches = [
            [
                Reading(g.patient.id, g.patient.device_id, metric, value, at)
                for metric, value in g.snapshot(scenario_t, tick, at).items()
            ]
            for g in generators.values()
        ]
        await asyncio.gather(*(_deliver(client, cfg.transport, b, stats) for b in batches))
        tick += 1
        if tick % 10 == 0:
            log.info("run.progress", tick=tick, **vars(stats))
        if total is None or tick < total:
            await sleep(cfg.interval_s)
    log.info("run.finished", ticks=tick, **vars(stats))
    return stats
