"""Startup guard that refuses weak/default secrets outside local development (CONTRACTS 4.1)."""

from __future__ import annotations

from stream_processor.config import DEV_PSEUDONYM_KEY, Settings
from stream_processor.logging_setup import get_logger

MIN_SECRET_LENGTH = 32


class SecretConfigError(RuntimeError):
    """Raised at startup when a secret is unsafe for the current APP_ENV."""


def check_secrets(settings: Settings) -> None:
    """Fail fast in non-dev environments; warn once in dev.

    A secret is unsafe when empty, equal to its dev default or shorter than 32 characters. The
    message names the variable but never echoes its value.
    """
    if settings.app_env == "dev":
        get_logger().warning("running with dev secrets")
        return
    key = settings.log_pseudonym_key
    if not key or key == DEV_PSEUDONYM_KEY or len(key) < MIN_SECRET_LENGTH:
        raise SecretConfigError(
            f"LOG_PSEUDONYM_KEY must be set to a non-default value of at least "
            f"{MIN_SECRET_LENGTH} characters when APP_ENV={settings.app_env}"
        )
