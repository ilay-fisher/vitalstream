"""Command line: ``vitalstream-sim run`` and ``vitalstream-sim load``."""

from __future__ import annotations

import asyncio
import re
import uuid
from typing import Annotated

import structlog
import typer

from vitalstream_sim.client import IngestClient, Transport
from vitalstream_sim.load import LoadConfig, run_load
from vitalstream_sim.logs import configure_logging
from vitalstream_sim.runner import RunConfig, RunStats, run_simulation
from vitalstream_sim.scenarios import Scenario

app = typer.Typer(
    help="Synthetic patient vitals for VitalStream.", no_args_is_help=True, add_completion=False
)

UrlOption = Annotated[
    str, typer.Option(envvar="INGEST_GATEWAY_URL", help="Ingest gateway base URL.")
]
KeyOption = Annotated[
    str, typer.Option(envvar="INGEST_API_KEY", help="Value for the X-Api-Key header.")
]
LogLevelOption = Annotated[str, typer.Option(envvar="LOG_LEVEL", help="Log level.")]


def parse_speed(text: str) -> float:
    """``10x``, ``10`` or ``0.5X`` to a positive multiplier."""
    match = re.fullmatch(r"\s*(\d+(?:\.\d+)?)\s*[xX]?\s*", text)
    if not match or float(match.group(1)) <= 0:
        raise ValueError(f"invalid speed {text!r}, expected a positive number such as 10x")
    return float(match.group(1))


def parse_assignments(items: list[str]) -> dict[str, Scenario]:
    """``P-0003=sepsis`` pairs to a patient -> scenario mapping."""
    result: dict[str, Scenario] = {}
    for item in items:
        patient, separator, name = item.partition("=")
        if not separator or not patient:
            raise ValueError(f"invalid assignment {item!r}, expected P-0003=sepsis")
        try:
            result[patient.strip()] = Scenario(name.strip())
        except ValueError:
            raise ValueError(f"unknown scenario {name!r} in assignment {item!r}") from None
    return result


def _start(level: str) -> None:
    configure_logging(level)
    structlog.contextvars.bind_contextvars(correlationId=str(uuid.uuid4()))


@app.command()
def run(
    scenario: Annotated[
        Scenario, typer.Option(envvar="SIM_SCENARIO", help="Scenario for the affected patients.")
    ] = Scenario.STABLE,
    patients: Annotated[
        int, typer.Option(envvar="SIM_PATIENTS", min=1, max=20, help="Patients P-0001..N.")
    ] = 5,
    affected: Annotated[
        int | None,
        typer.Option(
            envvar="SIM_AFFECTED", min=0, help="Only the first N patients get --scenario."
        ),
    ] = None,
    assign: Annotated[
        list[str] | None,
        typer.Option(help="Per-patient override, repeatable: --assign P-0003=sepsis."),
    ] = None,
    speed: Annotated[
        str, typer.Option(envvar="SIM_SPEED", help="Scenario speed vs wall clock, e.g. 10x.")
    ] = "1x",
    transport: Annotated[
        Transport, typer.Option(envvar="SIM_TRANSPORT", help="Wire format.")
    ] = Transport.BATCH,
    interval: Annotated[
        float, typer.Option(envvar="SIM_INTERVAL", min=0.0, help="Seconds between readings.")
    ] = 1.0,
    duration: Annotated[
        float | None, typer.Option(envvar="SIM_DURATION", help="Stop after N seconds.")
    ] = None,
    ticks: Annotated[int | None, typer.Option(min=1, help="Stop after N sampling rounds.")] = None,
    seed: Annotated[int, typer.Option(envvar="SIM_SEED")] = 42,
    url: UrlOption = "http://localhost:8081",
    api_key: KeyOption = "dev-ingest-key",
    log_level: LogLevelOption = "INFO",
) -> None:
    """Stream scenario vitals for N patients until interrupted (or --duration / --ticks)."""
    try:
        cfg = RunConfig(
            patients=patients,
            default_scenario=scenario,
            affected=affected,
            assignments=parse_assignments(assign or []),
            speed=parse_speed(speed),
            interval_s=interval,
            duration_s=duration,
            max_ticks=ticks,
            transport=transport,
            seed=seed,
        )
    except ValueError as error:
        raise typer.BadParameter(str(error)) from error
    _start(log_level)
    stats = RunStats()

    async def main() -> None:
        client = IngestClient(url, api_key)
        try:
            await run_simulation(cfg, client, stats=stats)
        finally:
            await client.aclose()

    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        typer.echo("interrupted")
    typer.echo(stats.render())


@app.command()
def load(
    rate: Annotated[int, typer.Option(min=1, help="Target events (readings) per second.")] = 2000,
    duration: Annotated[float, typer.Option(min=0.1, help="Seconds to sustain the rate.")] = 10.0,
    batch_size: Annotated[
        int, typer.Option(min=1, max=500, help="Readings per request (one patient each).")
    ] = 50,
    concurrency: Annotated[int, typer.Option(min=1, help="Max in-flight requests.")] = 64,
    transport: Annotated[Transport, typer.Option(help="Wire format.")] = Transport.BATCH,
    run_id: Annotated[
        str | None, typer.Option(help="Reuse to resend identical event ids (duplicates).")
    ] = None,
    seed: Annotated[int, typer.Option()] = 42,
    url: UrlOption = "http://localhost:8081",
    api_key: KeyOption = "dev-ingest-key",
    log_level: LogLevelOption = "WARNING",
) -> None:
    """Sustain --rate events/s for --duration and print p50/p95 latency and outcome counts."""
    _start(log_level)
    cfg = LoadConfig(
        rate=rate, duration_s=duration, batch_size=batch_size, concurrency=concurrency,
        transport=transport, seed=seed, run_id=run_id,
    )  # fmt: skip

    async def main() -> None:
        client = IngestClient(url, api_key)
        try:
            report = await run_load(cfg, client)
        finally:
            await client.aclose()
        typer.echo(report.render())

    asyncio.run(main())


def main() -> None:
    """Console-script entry point."""
    app()
