"""Static checks on the delivery files (no Docker, no network).

These stand in for a schema validator: they catch the mistakes that make a pipeline silently
useless (unpinned actions, a stage that references a missing job, a forbidden n8n node).
"""

import json
import re
from pathlib import Path

import pytest
import yaml

REPO = Path(__file__).resolve().parents[3]
SERVICES = {
    "ingest-gateway",
    "stream-processor",
    "clinical-api",
    "copilot",
    "simulator",
    "dashboard",
}
FORBIDDEN_N8N = {
    "n8n-nodes-base.executeCommand",
    "n8n-nodes-base.ssh",
    "n8n-nodes-base.code",
}


def load_yaml(relative: str) -> dict:
    return yaml.safe_load((REPO / relative).read_text())


@pytest.mark.parametrize("workflow", ["ci.yml", "release.yml"])
def test_third_party_actions_are_pinned_to_commit_shas(workflow):
    text = (REPO / ".github/workflows" / workflow).read_text()

    uses = re.findall(r"uses:\s*(\S+)", text)

    assert uses
    for ref in uses:
        assert re.fullmatch(r"[\w./-]+@[0-9a-f]{40}", ref), f"{ref} is not pinned to a commit SHA"


def test_ci_builds_scans_and_pushes_every_service_image():
    ci = load_yaml(".github/workflows/ci.yml")
    build = ci["jobs"]["build"]

    matrix = {entry["service"] for entry in build["strategy"]["matrix"]["include"]}
    steps = {step.get("name", step.get("uses", "")): step for step in build["steps"]}

    assert matrix == SERVICES
    assert build["permissions"]["packages"] == "write"
    assert any("trivy" in s.get("uses", "") for s in build["steps"])
    trivy = next(s for s in build["steps"] if "trivy" in s.get("uses", ""))
    assert trivy["with"]["severity"] == "CRITICAL"
    assert trivy["with"]["exit-code"] == "1"
    push = steps["Push image (sha + latest)"]
    assert "refs/heads/main" in push["if"]
    assert "${{ github.sha }}" in push["with"]["tags"]
    assert ":latest" in push["with"]["tags"]
    assert ci["env"]["IMAGE_PREFIX"] == "ghcr.io/ilay-fisher/vitalstream"
    assert "gha" in build["steps"][2]["with"]["cache-from"]


def test_ci_runs_tflint_and_e2e_after_build():
    ci = load_yaml(".github/workflows/ci.yml")

    assert "build" in ci["jobs"]["e2e"]["needs"]
    tflint_steps = [s.get("uses", "") for s in ci["jobs"]["tflint"]["steps"]]
    assert any(u.startswith("terraform-linters/setup-tflint@") for u in tflint_steps)
    assert ci["permissions"] == {"contents": "read"}


def test_release_publishes_semver_tags_on_version_tags():
    release = load_yaml(".github/workflows/release.yml")

    assert release[True]["push"]["tags"] == ["v*.*.*"]  # PyYAML parses `on:` as True
    assert release["jobs"]["publish"]["permissions"]["packages"] == "write"


def test_gitlab_jobs_use_declared_stages_and_resolvable_references():
    gitlab = load_yaml(".gitlab-ci.yml")
    stages = gitlab["stages"]
    jobs = {k: v for k, v in gitlab.items() if not k.startswith(".") and isinstance(v, dict)}
    hidden = {k for k in gitlab if k.startswith(".")}
    jobs.pop("default", None)
    jobs.pop("variables", None)
    jobs.pop("cache", None)

    assert stages == ["lint", "unit", "integration", "build", "e2e"]
    for name, job in jobs.items():
        for parent in job.get("extends", []):
            assert parent in hidden, f"{name} extends unknown template {parent}"
        for need in job.get("needs", []):
            assert need["job"] in jobs, f"{name} needs unknown job {need['job']}"
        if "stage" in job:
            assert job["stage"] in stages, name


def test_gitlab_has_a_path_scoped_job_per_service_and_stage():
    gitlab = load_yaml(".gitlab-ci.yml")

    for stage in ("lint", "unit", "integration", "build"):
        for service in SERVICES:
            assert f"{stage}:{service}" in gitlab, f"missing {stage}:{service}"
    for service in SERVICES:
        rules = gitlab[f".rules-{service}"]["rules"]
        assert any("changes" in rule for rule in rules), service


def test_jenkinsfile_declares_stages_in_order_with_balanced_braces():
    text = (REPO / "Jenkinsfile").read_text()
    top_level = re.findall(r"^    stage\('([^']+)'\)", text, flags=re.MULTILINE)

    assert top_level == ["Lint", "Unit", "Integration", "Build images", "E2E"]
    assert text.count("{") == text.count("}")
    for service in SERVICES:
        assert f"stage('{service}')" in text


@pytest.mark.parametrize("workflow", ["ci.yml", "release.yml"])
def test_trivy_scans_vulnerabilities_and_secrets_before_any_push(workflow):
    doc = load_yaml(f".github/workflows/{workflow}")
    job = next(iter(doc["jobs"].values()))
    names = [s.get("name", "") for s in job["steps"]]
    trivy = next(s for s in job["steps"] if "trivy" in s.get("uses", ""))

    assert trivy["with"]["scanners"] == "vuln,secret"
    assert trivy["with"]["exit-code"] == "1"
    assert trivy["env"]["TRIVY_SECRET_CONFIG"] == "e2e/ci/trivy-secret.yaml"
    scan = job["steps"].index(trivy)
    pushes = [i for i, s in enumerate(job["steps"]) if s.get("with", {}).get("push") is True]
    assert pushes and all(scan < i for i in pushes), names


@pytest.mark.parametrize("workflow", ["ci.yml", "release.yml"])
def test_trivy_ignore_policy_is_per_service_and_time_boxed(workflow):
    text = (REPO / ".github/workflows" / workflow).read_text()

    assert "TRIVY_IGNORE_POLICY: e2e/ci/trivy-ignore-${{ matrix.service }}.rego" in text
    for service in SERVICES:
        policy = (REPO / f"e2e/ci/trivy-ignore-{service}.rego").read_text()
        assert "package trivy" in policy
        assert "PkgName ==" not in policy and "input.PkgName in" not in policy, service
        for rule in policy.split("ignore if {")[1:]:
            assert "time.now_ns() < " in rule, f"{service}: ignore rule without expiry"
            assert "# Reason:" in policy and "# Review:" in policy, service
    assert not (REPO / "e2e/ci/trivy-ignore.rego").exists()


def test_trivy_secret_allowlist_is_scoped_to_one_path():
    config = yaml.safe_load((REPO / "e2e/ci/trivy-secret.yaml").read_text())

    rules = config["allow-rules"]
    assert rules
    for rule in rules:
        assert rule["path"] and rule["path"] != ".*"
        assert "id" not in rule or rule["description"]


def n8n_workflow() -> dict:
    return json.loads((REPO / "infra/n8n/critical-alert-escalation.json").read_text())[0]


def test_n8n_workflow_avoids_nodes_excluded_by_the_platform():
    types = {node["type"] for node in n8n_workflow()["nodes"]}

    assert not types & FORBIDDEN_N8N
    assert "n8n-nodes-base.rabbitmqTrigger" in types
    assert "n8n-nodes-base.wait" in types


def test_n8n_workflow_wiring_is_consistent():
    workflow = n8n_workflow()
    names = {node["name"] for node in workflow["nodes"]}

    assert workflow["name"] == "critical-alert-escalation"
    for source, outputs in workflow["connections"].items():
        assert source in names
        for branch in outputs["main"]:
            for target in branch:
                assert target["node"] in names
    trigger = next(n for n in workflow["nodes"] if n["type"].endswith("rabbitmqTrigger"))
    assert trigger["parameters"]["queue"] == "notify.critical"


def test_n8n_workflow_contains_no_real_endpoint_or_secret():
    text = (REPO / "infra/n8n/critical-alert-escalation.json").read_text()

    assert "$env.ESCALATION_WEBHOOK_URL" in text
    assert not re.search(r"https?://(?!clinical-api)", text)
