"""Bring the compose stack up and down the same way the root Makefile does.

The Makefile discovers component fragments with a wildcard; this module mirrors that so a new
component is picked up automatically, and adds ``e2e/compose.e2e.yaml`` for e2e-only overrides
(heap caps per CONTRIBUTING section 7, and the stream-processor build context).
"""

from __future__ import annotations

import enum
import subprocess
from pathlib import Path

COMPONENTS = ("ingest-gateway", "stream-processor", "clinical-api", "copilot", "dashboard")
FRAGMENT_GLOBS = ("services/*/compose.yaml", "tools/*/compose.yaml", "web/*/compose.yaml")
OVERRIDE = "e2e/compose.e2e.yaml"
PROJECT_NAME = "vitalstream"


class TeardownMode(enum.Enum):
    """What the session fixture removes when the run ends."""

    NONE = "none"
    SERVICES = "services"
    ALL = "all"


def resolve_teardown(value: str | None, *, infra_was_running: bool) -> TeardownMode:
    """Decide the teardown scope from ``E2E_TEARDOWN`` (unset means auto).

    Auto never stops infrastructure that was already running before the suite started: on a
    shared developer machine other people's work may depend on it.
    """
    if value is None:
        return TeardownMode.SERVICES if infra_was_running else TeardownMode.ALL
    try:
        return TeardownMode(value)
    except ValueError:
        allowed = ", ".join(m.value for m in TeardownMode)
        raise ValueError(f"E2E_TEARDOWN must be one of: {allowed} (got {value!r})") from None


def discover_fragments(repo: Path) -> list[str]:
    """Component compose fragments in the Makefile's order, as repo-relative paths."""
    found: list[str] = []
    for pattern in FRAGMENT_GLOBS:
        found.extend(sorted(p.relative_to(repo).as_posix() for p in repo.glob(pattern)))
    return found


def compose_command(repo: Path, *args: str) -> list[str]:
    """``docker compose`` over infra + every fragment + the e2e override."""
    files = ["infra/compose.yaml", *discover_fragments(repo)]
    if (repo / OVERRIDE).exists():
        files.append(OVERRIDE)
    command = ["docker", "compose", "--project-directory", str(repo)]
    for file in files:
        command += ["-f", str(repo / file)]
    return [*command, *args]


def simulator_command(
    repo: Path, *, patient: str, scenario: str, duration: int, speed: str
) -> list[str]:
    """One-shot simulator run in the stack's network driving a single patient through a scenario."""
    number = int(patient.removeprefix("P-"))
    return compose_command(
        repo,
        "run",
        "--rm",
        "--no-deps",
        "simulator",
        "run",
        "--assign",
        f"{patient}={scenario}",
        "--patients",
        str(number),
        "--speed",
        speed,
        "--duration",
        str(duration),
    )


def run(command: list[str], *, env: dict[str, str] | None = None, check: bool = True) -> str:
    """Run a command, echo it for the CI log, and return combined output."""
    print("$", " ".join(command), flush=True)
    result = subprocess.run(command, capture_output=True, text=True, env=env, check=False)
    output = result.stdout + result.stderr
    if check and result.returncode != 0:
        raise RuntimeError(
            f"{' '.join(command[:6])} failed ({result.returncode}):\n{output[-4000:]}"
        )
    return output


def container_running(name: str) -> bool:
    """True when a container with exactly this name is running."""
    out = run(["docker", "ps", "--filter", f"name=^{name}$", "--format", "{{.Names}}"], check=False)
    return name in out.split()
