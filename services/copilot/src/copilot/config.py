"""Twelve-factor configuration: everything comes from environment variables."""

from functools import lru_cache
from pathlib import Path
from typing import Literal
from urllib.parse import urlsplit

from pydantic import SecretStr
from pydantic_settings import BaseSettings, SettingsConfigDict

_MIN_SECRET_LENGTH = 32
_DEFAULT_CORPUS = Path(__file__).resolve().parents[2] / "corpus"


class Settings(BaseSettings):
    """Runtime settings. Defaults work against the `infra/` compose stack on localhost."""

    model_config = SettingsConfigDict(env_file=None, extra="ignore", case_sensitive=False)

    app_env: str = "dev"
    service_name: str = "copilot"
    log_level: str = "INFO"
    port: int = 8083

    postgres_url: str = "postgresql://vitalstream:vitalstream@localhost:5433/vitalstream"
    elasticsearch_url: str = "http://localhost:9200"
    es_index: str = "guidelines"
    clinical_api_url: str = "http://localhost:8082"
    http_timeout_seconds: float = 10.0

    copilot_admin_token: SecretStr = SecretStr("dev-admin-token")
    mcp_token: SecretStr = SecretStr("dev-mcp-token")
    log_pseudonym_key: SecretStr = SecretStr("dev-only-pseudonym-key")
    mcp_allowed_hosts: str = "localhost:8083,127.0.0.1:8083,copilot:8083"

    llm_provider: Literal["fake", "anthropic"] = "fake"
    anthropic_api_key: SecretStr | None = None
    llm_model: str = "claude-sonnet-5-5"
    llm_max_tokens: int = 1024

    embedder: Literal["fastembed", "hash"] = "fastembed"
    embedding_model: str = "BAAI/bge-small-en-v1.5"
    fastembed_cache_path: str | None = None
    corpus_dir: Path = _DEFAULT_CORPUS
    ingest_on_startup: bool = True
    chunk_tokens: int = 400
    chunk_overlap_tokens: int = 60
    rrf_k: int = 60
    retrieval_candidates: int = 20

    session_ttl_seconds: float = 1800.0
    max_sessions: int = 1000
    max_history_messages: int = 20
    max_agent_steps: int = 6

    cors_origins: str = (
        "http://localhost:3000,http://localhost:5173,http://127.0.0.1:3000,http://127.0.0.1:5173"
    )

    @property
    def sqlalchemy_url(self) -> str:
        """The Postgres URL rewritten for the asyncpg driver."""
        url = self.postgres_url
        for prefix in ("postgresql://", "postgres://"):
            if url.startswith(prefix):
                return "postgresql+asyncpg://" + url[len(prefix) :]
        return url

    def validate_secrets(self) -> None:
        """Fail fast outside dev when a secret is empty, a dev default or shorter than 32 chars.

        The error names the offending settings, never their values.
        """
        if self.app_env == "dev":
            return
        db_password = urlsplit(self.postgres_url).password or ""
        candidates = {
            "COPILOT_ADMIN_TOKEN": (self.copilot_admin_token.get_secret_value(), "dev-admin-token"),
            "MCP_TOKEN": (self.mcp_token.get_secret_value(), "dev-mcp-token"),
            "LOG_PSEUDONYM_KEY": (
                self.log_pseudonym_key.get_secret_value(),
                "dev-only-pseudonym-key",
            ),
            "database password (POSTGRES_URL)": (db_password, "vitalstream"),
        }
        weak = [
            name
            for name, (value, dev_default) in candidates.items()
            if len(value) < _MIN_SECRET_LENGTH or value == dev_default
        ]
        if weak:
            raise ValueError(
                f"APP_ENV={self.app_env}: refusing to start with weak secrets "
                f"(empty, dev default or < {_MIN_SECRET_LENGTH} chars): {', '.join(weak)}"
            )

    @property
    def mcp_host_list(self) -> list[str]:
        """Host header values accepted by /mcp (DNS-rebinding protection)."""
        return [h.strip() for h in self.mcp_allowed_hosts.split(",") if h.strip()]

    @property
    def cors_origin_list(self) -> list[str]:
        """CORS origins as a list (env var is comma separated)."""
        return [origin.strip() for origin in self.cors_origins.split(",") if origin.strip()]


@lru_cache
def get_settings() -> Settings:
    """Process-wide settings singleton."""
    return Settings()
