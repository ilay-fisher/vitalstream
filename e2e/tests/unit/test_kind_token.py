"""deploy/kind/mint-service-token.py mints the copilot's SERVICE token for the kind secret."""

from __future__ import annotations

import base64
import hashlib
import hmac
import importlib.util
import json
import re
import subprocess
import sys
from pathlib import Path

import pytest

REPO = Path(__file__).resolve().parents[3]
SCRIPT = REPO / "deploy/kind/mint-service-token.py"
SECRET = "k" * 64
NOW = 1_800_000_000


def load_module():  # type: ignore[no-untyped-def]
    spec = importlib.util.spec_from_file_location("mint_service_token", SCRIPT)
    assert spec and spec.loader
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def b64d(part: str) -> bytes:
    return base64.urlsafe_b64decode(part + "=" * (-len(part) % 4))


def test_token_has_the_claims_the_resource_server_requires() -> None:
    token = load_module().mint(SECRET, "vitalstream-dev", now=NOW)

    header, payload, signature = token.split(".")
    claims = json.loads(b64d(payload))
    assert json.loads(b64d(header)) == {"alg": "HS256", "typ": "JWT"}
    assert claims["iss"] == "vitalstream-dev"
    assert claims["aud"] == ["clinical-api"]
    assert claims["sub"] == "copilot-mcp"
    assert claims["roles"] == ["SERVICE"]
    assert claims["iat"] == NOW
    assert claims["exp"] - NOW == 30 * 24 * 3600
    expected = hmac.new(
        SECRET.encode(), f"{header}.{payload}".encode(), hashlib.sha256
    ).digest()
    assert b64d(signature) == expected


def test_a_custom_issuer_is_used() -> None:
    token = load_module().mint(SECRET, "https://idp.example", now=NOW)

    assert json.loads(b64d(token.split(".")[1]))["iss"] == "https://idp.example"


def test_a_secret_shorter_than_32_bytes_is_refused() -> None:
    with pytest.raises(ValueError, match="32"):
        load_module().mint("short", "vitalstream-dev", now=NOW)


def test_cli_reads_the_secret_from_the_environment_never_from_argv() -> None:
    result = subprocess.run(
        [sys.executable, str(SCRIPT)],
        env={"JWT_SECRET": SECRET, "JWT_ISSUER": "vitalstream-dev"},
        capture_output=True,
        text=True,
        check=True,
    )

    assert re.fullmatch(r"[\w-]+\.[\w-]+\.[\w-]+\n", result.stdout)
    assert SECRET not in result.stdout + result.stderr


def test_cli_without_a_secret_fails() -> None:
    result = subprocess.run(
        [sys.executable, str(SCRIPT)], env={}, capture_output=True, text=True, check=False
    )

    assert result.returncode != 0


def test_up_sh_creates_both_secrets_and_never_prints_the_token() -> None:
    text = (REPO / "deploy/kind/up.sh").read_text()

    assert "CLINICAL_JWT_SECRET" in text
    assert "CLINICAL_API_TOKEN" in text
    assert "mint-service-token.py" in text
    assert "openssl rand -hex 32" in text
    assert not re.search(r"echo[^\n]*(TOKEN|token)", text)
