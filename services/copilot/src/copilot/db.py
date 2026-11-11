"""Async engine/session helpers and programmatic Alembic migrations."""

import asyncio
from pathlib import Path

from alembic import command
from alembic.config import Config
from sqlalchemy.ext.asyncio import AsyncEngine, async_sessionmaker, create_async_engine


def create_engine(sqlalchemy_url: str) -> AsyncEngine:
    """Async engine with a small pool (this service is a light DB client)."""
    return create_async_engine(sqlalchemy_url, pool_size=5, max_overflow=5, pool_pre_ping=True)


def session_factory(engine: AsyncEngine) -> async_sessionmaker:  # type: ignore[type-arg]
    """Session factory that keeps objects usable after commit."""
    return async_sessionmaker(engine, expire_on_commit=False)


def _alembic_config(sqlalchemy_url: str) -> Config:
    config = Config()
    config.set_main_option("script_location", str(Path(__file__).parent / "migrations"))
    config.set_main_option("sqlalchemy.url", sqlalchemy_url.replace("%", "%%"))
    return config


async def run_migrations(sqlalchemy_url: str) -> None:
    """`alembic upgrade head`, off the event loop because env.py drives its own loop."""
    await asyncio.to_thread(command.upgrade, _alembic_config(sqlalchemy_url), "head")
