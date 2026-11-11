"""structlog JSON logging with the mandatory `service` and `correlationId` fields."""

import logging
import sys
from typing import Any

import structlog
from structlog.typing import EventDict, WrappedLogger

from copilot.correlation import get_correlation_id


class _StdoutHandler(logging.StreamHandler):  # type: ignore[type-arg]
    """Always writes to the *current* sys.stdout (keeps working when stdout is swapped)."""

    def __init__(self) -> None:
        super().__init__()

    @property
    def stream(self) -> Any:
        return sys.stdout

    @stream.setter
    def stream(self, _: Any) -> None:
        return None


def _add_service_and_correlation(service: str) -> structlog.typing.Processor:
    def processor(_: WrappedLogger, __: str, event_dict: EventDict) -> EventDict:
        event_dict["service"] = service
        event_dict["correlationId"] = get_correlation_id()
        return event_dict

    return processor


def _rename_event_to_message(_: WrappedLogger, __: str, event_dict: EventDict) -> EventDict:
    event_dict["message"] = event_dict.pop("event", "")
    return event_dict


def configure_logging(service: str, level: str = "INFO") -> None:
    """Route structlog and stdlib logging (uvicorn, sqlalchemy, ...) to JSON on stdout."""
    shared: list[structlog.typing.Processor] = [
        structlog.contextvars.merge_contextvars,
        structlog.stdlib.add_log_level,
        structlog.processors.TimeStamper(fmt="iso", utc=True, key="timestamp"),
        _add_service_and_correlation(service),
    ]
    structlog.configure(
        processors=[
            *shared,
            structlog.stdlib.ProcessorFormatter.wrap_for_formatter,
        ],
        logger_factory=structlog.stdlib.LoggerFactory(),
        wrapper_class=structlog.stdlib.BoundLogger,
        cache_logger_on_first_use=True,
    )
    formatter = structlog.stdlib.ProcessorFormatter(
        foreign_pre_chain=shared,
        processors=[
            structlog.stdlib.ProcessorFormatter.remove_processors_meta,
            structlog.processors.format_exc_info,
            _rename_event_to_message,
            structlog.processors.JSONRenderer(),
        ],
    )
    handler = _StdoutHandler()
    handler.setFormatter(formatter)
    root = logging.getLogger()
    root.handlers = [handler]
    root.setLevel(level.upper())
    logging.getLogger("uvicorn.access").disabled = True
    for noisy in ("httpx", "httpcore", "elastic_transport", "sqlalchemy.engine"):
        logging.getLogger(noisy).setLevel(logging.WARNING)
