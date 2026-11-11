"""Alembic environment (async, asyncpg). Run via `copilot.db.run_migrations`."""

import asyncio

from alembic import context
from sqlalchemy import text
from sqlalchemy.engine import Connection
from sqlalchemy.ext.asyncio import create_async_engine

from copilot.rag.models import SCHEMA, Base

config = context.config
target_metadata = Base.metadata
_MIGRATION_LOCK = 727274  # serialises concurrent replicas starting up together


def _run(connection: Connection) -> None:
    connection.execute(text(f"CREATE SCHEMA IF NOT EXISTS {SCHEMA}"))
    connection.execute(text("CREATE EXTENSION IF NOT EXISTS vector"))
    context.configure(
        connection=connection,
        target_metadata=target_metadata,
        version_table_schema=SCHEMA,
        include_schemas=True,
    )
    with context.begin_transaction():
        connection.execute(text(f"SELECT pg_advisory_xact_lock({_MIGRATION_LOCK})"))
        context.run_migrations()


async def _run_async() -> None:
    engine = create_async_engine(config.get_main_option("sqlalchemy.url") or "")
    async with engine.connect() as connection:
        await connection.run_sync(_run)
        await connection.commit()
    await engine.dispose()


asyncio.run(_run_async())
