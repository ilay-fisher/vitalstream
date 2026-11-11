"""Structured JSON logging: every line carries ``service`` and ``correlationId``."""

from __future__ import annotations

import logging
from collections.abc import MutableMapping
from typing import Any

import structlog

SERVICE = "simulator"


def _add_service(_: Any, __: str, event_dict: MutableMapping[str, Any]) -> MutableMapping[str, Any]:
    event_dict["service"] = SERVICE
    event_dict.setdefault("correlationId", "-")
    return event_dict


def _rename_event(
    _: Any, __: str, event_dict: MutableMapping[str, Any]
) -> MutableMapping[str, Any]:
    event_dict["message"] = event_dict.pop("event")
    return event_dict


def configure_logging(level: str = "INFO") -> None:
    """Route structlog to stdout as one JSON object per line."""
    structlog.configure(
        processors=[
            structlog.contextvars.merge_contextvars,
            structlog.processors.add_log_level,
            structlog.processors.TimeStamper(fmt="iso", utc=True, key="timestamp"),
            _add_service,
            _rename_event,
            structlog.processors.JSONRenderer(),
        ],
        wrapper_class=structlog.make_filtering_bound_logger(logging.getLevelName(level.upper())),
        logger_factory=structlog.PrintLoggerFactory(),
        cache_logger_on_first_use=False,
    )


def get_logger() -> Any:
    """Logger bound to the simulator service."""
    return structlog.get_logger()
