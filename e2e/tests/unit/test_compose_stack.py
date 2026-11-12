"""Static checks on the compose files that `make up` merges with `--project-directory .`.

They parse the YAML instead of calling `docker compose config`, so no Docker is needed.
"""

from pathlib import Path

import pytest
import yaml

REPO = Path(__file__).resolve().parents[3]
FRAGMENTS = sorted(
    p.relative_to(REPO).as_posix()
    for pattern in ("services/*/compose.yaml", "tools/*/compose.yaml", "web/*/compose.yaml")
    for p in REPO.glob(pattern)
)


def load(rel: str) -> dict:
    return yaml.safe_load((REPO / rel).read_text())


@pytest.mark.parametrize("fragment", FRAGMENTS)
def test_build_context_is_the_fragment_directory(fragment):
    """Contexts resolve against the repo root, so `.` would point at the wrong directory."""
    expected = Path(fragment).parent.as_posix()

    for name, service in load(fragment)["services"].items():
        build = service.get("build")
        if build is None:
            continue
        context = build if isinstance(build, str) else build.get("context", ".")
        assert (Path(context).as_posix().rstrip("/")) == expected, name


@pytest.mark.parametrize("fragment", FRAGMENTS)
def test_fragment_does_not_redefine_shared_network_as_default(fragment):
    """The external network is keyed `vitalstream`; naming it under `default` makes compose reject it."""
    networks = load(fragment).get("networks", {})

    assert "default" not in networks


# Healthchecks that predate this rule and are verified healthy on Colima with `localhost`
# (their servers listen on both stacks). New or changed healthchecks must use 127.0.0.1.
LOCALHOST_GRANDFATHERED = {
    "kafka", "kafka-exporter", "kafka-ui", "elasticsearch", "kibana", "localstack",
    "prometheus", "grafana", "ingest-gateway",
}


def healthchecks() -> list[tuple[str, str]]:
    found = []
    for rel in ["infra/compose.yaml", *FRAGMENTS]:
        for name, service in load(rel)["services"].items():
            test = service.get("healthcheck", {}).get("test")
            if test:
                found.append((name, " ".join(test)))
    return found


@pytest.mark.parametrize(("service", "test"), healthchecks())
def test_healthcheck_avoids_localhost(service, test):
    """Colima resolves localhost to ::1; a service listening on IPv4 only then stays unhealthy."""
    if service in LOCALHOST_GRANDFATHERED:
        pytest.skip("grandfathered, dual-stack listener")

    assert "localhost" not in test


@pytest.mark.parametrize("service", ["n8n", "n8n-proxy"])
def test_n8n_healthchecks_use_ipv4_loopback(service):
    test = dict(healthchecks())[service]

    assert "127.0.0.1" in test


def up_recipe() -> str:
    lines = (REPO / "Makefile").read_text().splitlines()
    start = next(i for i, line in enumerate(lines) if line.startswith("up:"))
    end = next((i for i in range(start + 1, len(lines)) if lines[i] and not lines[i].startswith(("\t", "#"))), len(lines))
    return "\n".join(lines[start:end])


def test_up_waits_only_on_non_init_services():
    """`--wait` treats exited one-shot init containers as failures, so they must be excluded."""
    recipe = up_recipe()
    wait_lines = [line for line in recipe.splitlines() if "--wait" in line]

    assert wait_lines
    assert all("grep -v '^init-'" in line for line in wait_lines)
    assert "--build" in recipe
    assert "--wait-timeout 600" in recipe


def test_up_checks_init_containers_exit_code():
    assert "docker wait" in up_recipe()
