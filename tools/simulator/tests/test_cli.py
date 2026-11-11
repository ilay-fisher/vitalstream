from __future__ import annotations

import json

import httpx
import pytest
import respx
from typer.testing import CliRunner

from vitalstream_sim.cli import app, parse_assignments, parse_speed
from vitalstream_sim.scenarios import Scenario

BASE = "http://gw.test:8081"
runner = CliRunner()


@pytest.mark.parametrize(
    ("text", "value"), [("10x", 10.0), ("10", 10.0), ("0.5X", 0.5), (" 2x ", 2.0)]
)
def test_parse_speed(text: str, value: float) -> None:
    assert parse_speed(text) == value


@pytest.mark.parametrize("text", ["fast", "0x", "-3x", ""])
def test_parse_speed_rejects_garbage(text: str) -> None:
    with pytest.raises(ValueError, match="speed"):
        parse_speed(text)


def test_parse_assignments() -> None:
    got = parse_assignments(["P-0003=sepsis", "P-0007=hypoxia"])
    assert got == {"P-0003": Scenario.SEPSIS, "P-0007": Scenario.HYPOXIA}
    with pytest.raises(ValueError, match="assignment"):
        parse_assignments(["P-0003"])
    with pytest.raises(ValueError, match="scenario"):
        parse_assignments(["P-0003=bogus"])


def test_help_lists_commands() -> None:
    result = runner.invoke(app, ["--help"])
    assert result.exit_code == 0
    assert "run" in result.output
    assert "load" in result.output


@respx.mock
def test_run_command_end_to_end() -> None:
    route = respx.post(f"{BASE}/api/v1/vitals/batch").mock(
        side_effect=lambda req: httpx.Response(
            202,
            json={
                "accepted": len(json.loads(req.content)["readings"]),
                "duplicates": 0,
                "rejected": [],
            },
        )
    )
    result = runner.invoke(
        app,
        [
            "run", "--scenario", "sepsis", "--patients", "2", "--speed", "10x",
            "--ticks", "2", "--interval", "0", "--url", BASE, "--api-key", "k",
        ],
    )  # fmt: skip
    assert result.exit_code == 0, result.output
    assert route.call_count == 4
    assert "accepted=24" in result.output


@respx.mock
def test_load_command_prints_latency_summary() -> None:
    respx.post(f"{BASE}/api/v1/vitals/batch").respond(
        202, json={"accepted": 10, "duplicates": 0, "rejected": []}
    )
    result = runner.invoke(
        app,
        [
            "load",
            "--rate",
            "50",
            "--duration",
            "0.2",
            "--batch-size",
            "10",
            "--url",
            BASE,
            "--api-key",
            "k",
        ],
    )
    assert result.exit_code == 0, result.output
    assert "p95" in result.output


def test_run_rejects_bad_speed() -> None:
    result = runner.invoke(app, ["run", "--speed", "warp"])
    assert result.exit_code != 0
