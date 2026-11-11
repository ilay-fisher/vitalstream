"""Twelve-factor configuration: everything comes from environment variables."""

from functools import lru_cache
from pathlib import Path
from typing import Literal

from pydantic import SecretStr
from pydantic_settings import BaseSettings, SettingsConfigDict

_DEFAULT_CORPUS = Path(__file__).resolve().parents[2] / "corpus"


class Settings(BaseSettings):
    """Runtime settings. Defaults work against the `infra/` compose stack on localhost."""

    model_config = SettingsConfigDict(env_file=None, extra="ignore", case_sensitive=False)

    service_name: str = "copilot"
    log_level: str = "INFO"
    port: int = 8083

    postgres_url: str = "postgresql://vitalstream:vitalstream@localhost:5433/vitalstream"
    elasticsearch_url: str = "http://localhost:9200"
    es_index: str = "guidelines"
    clinical_api_url: str = "http://localhost:8082"
    http_timeout_seconds: float = 10.0

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

    @property
    def cors_origin_list(self) -> list[str]:
        """CORS origins as a list (env var is comma separated)."""
        return [origin.strip() for origin in self.cors_origins.split(",") if origin.strip()]


@lru_cache
def get_settings() -> Settings:
    """Process-wide settings singleton."""
    return Settings()
