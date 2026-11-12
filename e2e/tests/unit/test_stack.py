from pathlib import Path

import pytest

from vitalstream_e2e.stack import (
    COMPONENTS,
    TeardownMode,
    compose_command,
    discover_fragments,
    resolve_teardown,
    simulator_command,
)


def make_repo(tmp_path: Path) -> Path:
    for rel in [
        "infra/compose.yaml",
        "services/clinical-api/compose.yaml",
        "services/ingest-gateway/compose.yaml",
        "tools/simulator/compose.yaml",
        "web/dashboard/compose.yaml",
        "e2e/compose.e2e.yaml",
    ]:
        path = tmp_path / rel
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text("services: {}\n")
    return tmp_path


def test_fragments_follow_makefile_discovery_order(tmp_path):
    repo = make_repo(tmp_path)

    assert discover_fragments(repo) == [
        "services/clinical-api/compose.yaml",
        "services/ingest-gateway/compose.yaml",
        "tools/simulator/compose.yaml",
        "web/dashboard/compose.yaml",
    ]


def test_compose_command_uses_project_directory_infra_fragments_and_override(tmp_path):
    repo = make_repo(tmp_path)

    cmd = compose_command(repo, "up", "-d")

    assert cmd[:4] == ["docker", "compose", "--project-directory", str(repo)]
    files = [cmd[i + 1] for i, a in enumerate(cmd) if a == "-f"]
    assert files[0].endswith("infra/compose.yaml")
    assert files[-1].endswith("e2e/compose.e2e.yaml")
    assert any(f.endswith("services/clinical-api/compose.yaml") for f in files)
    assert cmd[-2:] == ["up", "-d"]


def test_simulator_command_targets_p0003_sepsis(tmp_path):
    repo = make_repo(tmp_path)

    cmd = simulator_command(repo, patient="P-0003", scenario="sepsis", duration=90, speed="20x")

    assert "run" in cmd and "--rm" in cmd
    tail = cmd[cmd.index("simulator") + 1 :]
    assert tail[0] == "run"
    assert tail[tail.index("--assign") + 1] == "P-0003=sepsis"
    assert tail[tail.index("--patients") + 1] == "3"
    assert tail[tail.index("--duration") + 1] == "90"
    assert tail[tail.index("--speed") + 1] == "20x"


def test_components_cover_every_runtime_service():
    assert set(COMPONENTS) == {
        "ingest-gateway",
        "stream-processor",
        "clinical-api",
        "copilot",
        "dashboard",
    }


@pytest.mark.parametrize(
    ("env", "infra_was_running", "expected"),
    [
        (None, True, TeardownMode.SERVICES),
        (None, False, TeardownMode.ALL),
        ("none", False, TeardownMode.NONE),
        ("all", True, TeardownMode.ALL),
        ("services", False, TeardownMode.SERVICES),
    ],
)
def test_teardown_never_stops_infra_we_did_not_start(env, infra_was_running, expected):
    assert resolve_teardown(env, infra_was_running=infra_was_running) is expected


def test_invalid_teardown_value_is_rejected():
    with pytest.raises(ValueError, match="E2E_TEARDOWN"):
        resolve_teardown("bogus", infra_was_running=False)
