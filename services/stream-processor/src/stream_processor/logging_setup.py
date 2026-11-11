"""structlog JSON logging with `service` and (when bound) `correlationId` on every line."""

from __future__ import annotations

import logging
import sys

import structlog


def configure_logging(service: str, level: str = "INFO") -> None:
    """Configure structlog to print one JSON object per line to stdout.

    `correlationId` rides on structlog's contextvars: call `bind_correlation_id` where a record's
    correlation id is known and every later log line in that context carries it.
    """
    numeric_level = logging.getLevelName(level.upper())
    if not isinstance(numeric_level, int):
        numeric_level = logging.INFO
    logging.basicConfig(format="%(message)s", stream=sys.stdout, level=numeric_level, force=True)
    structlog.configure(
        processors=[
            structlog.contextvars.merge_contextvars,
            structlog.processors.add_log_level,
            structlog.processors.TimeStamper(fmt="iso", utc=True, key="timestamp"),
            _add_service(service),
            structlog.processors.format_exc_info,
            _rename_event_to_message,
            structlog.processors.JSONRenderer(),
        ],
        wrapper_class=structlog.make_filtering_bound_logger(numeric_level),
        logger_factory=structlog.PrintLoggerFactory(file=sys.stdout),
        cache_logger_on_first_use=False,
    )


def _add_service(service: str) -> structlog.typing.Processor:
    def processor(
        _logger: structlog.typing.WrappedLogger,
        _name: str,
        event_dict: structlog.typing.EventDict,
    ) -> structlog.typing.EventDict:
        event_dict["service"] = service
        return event_dict

    return processor


def _rename_event_to_message(
    _logger: structlog.typing.WrappedLogger,
    _name: str,
    event_dict: structlog.typing.EventDict,
) -> structlog.typing.EventDict:
    event_dict["message"] = event_dict.pop("event")
    return event_dict


def bind_correlation_id(correlation_id: str | None) -> None:
    """Attach `correlationId` to all subsequent log lines in this context (None clears it)."""
    if correlation_id:
        structlog.contextvars.bind_contextvars(correlationId=correlation_id)
    else:
        structlog.contextvars.unbind_contextvars("correlationId")


def get_logger(name: str = "stream-processor") -> structlog.stdlib.BoundLogger:
    """Return a structlog logger."""
    return structlog.get_logger(name)  # type: ignore[no-any-return]
