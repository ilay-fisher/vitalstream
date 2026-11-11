import json

import pytest

from stream_processor.config import Settings
from stream_processor.logging_setup import configure_logging
from stream_processor.secret_guard import SecretConfigError, check_secrets

STRONG = "k" * 32


def test_app_env_defaults_to_dev() -> None:
    assert Settings.from_env({}).app_env == "dev"


def test_dev_warns_once_and_accepts_default_secret(capsys: pytest.CaptureFixture[str]) -> None:
    configure_logging("stream-processor", "INFO")
    check_secrets(Settings.from_env({}))
    lines = [json.loads(x) for x in capsys.readouterr().out.strip().splitlines()]
    assert [x["message"] for x in lines] == ["running with dev secrets"]
    assert lines[0]["level"] == "warning"


def test_non_dev_with_strong_key_passes_silently(capsys: pytest.CaptureFixture[str]) -> None:
    configure_logging("stream-processor", "INFO")
    check_secrets(Settings.from_env({"APP_ENV": "prod", "LOG_PSEUDONYM_KEY": STRONG}))
    assert capsys.readouterr().out == ""


@pytest.mark.parametrize(
    "key",
    ["", "dev-only-pseudonym-key", "short", "k" * 31],
)
def test_non_dev_rejects_weak_pseudonym_key(key: str) -> None:
    env = {"APP_ENV": "prod", "LOG_PSEUDONYM_KEY": key}
    with pytest.raises(SecretConfigError, match="LOG_PSEUDONYM_KEY"):
        check_secrets(Settings.from_env(env))


def test_non_dev_rejects_unset_key_falling_back_to_dev_default() -> None:
    with pytest.raises(SecretConfigError, match="LOG_PSEUDONYM_KEY"):
        check_secrets(Settings.from_env({"APP_ENV": "staging"}))


def test_error_never_contains_the_secret_value() -> None:
    with pytest.raises(SecretConfigError) as err:
        check_secrets(Settings.from_env({"APP_ENV": "prod", "LOG_PSEUDONYM_KEY": "tooshort"}))
    assert "tooshort" not in str(err.value)
