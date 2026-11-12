"""Secret startup guard (CONTRACTS §4.1): APP_ENV != dev refuses weak/default secrets."""

import json

import pytest
from pydantic import SecretStr

from copilot.app import create_app
from copilot.config import Settings
from copilot.security import bearer_matches

STRONG = "x" * 32
PG = f"postgresql://vitalstream:{STRONG}@db:5432/vitalstream"


def prod(**overrides: object) -> Settings:
    base: dict[str, object] = {
        "app_env": "prod",
        "copilot_admin_token": SecretStr(STRONG),
        "mcp_token": SecretStr(STRONG + "1"),
        "log_pseudonym_key": SecretStr(STRONG + "2"),
        "postgres_url": PG,
        "clinical_api_token": SecretStr("jwt." + "c" * 40),
    }
    return Settings(**{**base, **overrides})  # type: ignore[arg-type]


def test_dev_is_default_and_dev_secrets_are_allowed() -> None:
    settings = Settings()
    assert settings.app_env == "dev"
    settings.validate_secrets()


def test_prod_with_strong_secrets_passes() -> None:
    prod().validate_secrets()


@pytest.mark.parametrize(
    ("field", "value", "label"),
    [
        ("copilot_admin_token", SecretStr(""), "COPILOT_ADMIN_TOKEN"),
        ("copilot_admin_token", SecretStr("dev-admin-token"), "COPILOT_ADMIN_TOKEN"),
        ("mcp_token", SecretStr("short-token"), "MCP_TOKEN"),
        ("mcp_token", SecretStr("dev-mcp-token"), "MCP_TOKEN"),
        ("log_pseudonym_key", SecretStr("dev-only-pseudonym-key"), "LOG_PSEUDONYM_KEY"),
        ("log_pseudonym_key", SecretStr("y" * 31), "LOG_PSEUDONYM_KEY"),
        ("postgres_url", "postgresql://vitalstream:vitalstream@db:5432/vitalstream", "database"),
        ("postgres_url", "postgresql://vitalstream@db:5432/vitalstream", "database"),
        ("postgres_url", "postgresql://vitalstream:short@db:5432/vitalstream", "database"),
    ],
)
def test_prod_rejects_empty_default_or_short_secrets(field: str, value: object, label: str) -> None:
    with pytest.raises(ValueError, match=label) as error:
        prod(**{field: value}).validate_secrets()
    assert str(value) not in str(error.value) or not str(value)


def test_error_lists_every_offender_without_leaking_values() -> None:
    settings = prod(mcp_token=SecretStr("dev-mcp-token"), copilot_admin_token=SecretStr("abc"))
    with pytest.raises(ValueError) as error:
        settings.validate_secrets()
    message = str(error.value)
    assert "MCP_TOKEN" in message and "COPILOT_ADMIN_TOKEN" in message
    assert "dev-mcp-token" not in message and "abc" not in message


def test_create_app_refuses_to_start_with_weak_prod_secrets() -> None:
    with pytest.raises(ValueError, match="MCP_TOKEN"):
        create_app(prod(mcp_token=SecretStr("dev-mcp-token")))


def test_dev_startup_logs_single_warning(capsys: pytest.CaptureFixture[str]) -> None:
    create_app(Settings())
    lines = [json.loads(x) for x in capsys.readouterr().out.splitlines() if x.startswith("{")]
    warnings = [e for e in lines if e["message"] == "running with dev secrets"]
    assert len(warnings) == 1
    assert warnings[0]["level"] == "warning"


def test_prod_startup_does_not_warn(capsys: pytest.CaptureFixture[str]) -> None:
    create_app(prod())
    assert "running with dev secrets" not in capsys.readouterr().out


def test_bearer_never_matches_an_empty_expected_secret() -> None:
    assert not bearer_matches("Bearer ", "")
    assert not bearer_matches("Bearer", "")
    assert bearer_matches("Bearer abc", "abc")
