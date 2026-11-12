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


def test_n8n_healthcheck_avoids_localhost():
    """Colima resolves localhost to ::1 while n8n listens on IPv4 only."""
    test = load("infra/compose.yaml")["services"]["n8n"]["healthcheck"]["test"]

    assert "localhost" not in " ".join(test)
    assert "127.0.0.1" in " ".join(test)
