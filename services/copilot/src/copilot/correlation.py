"""Request-scoped correlation id, shared by logs, outbound HTTP and the ASGI middleware."""

from contextvars import ContextVar
from uuid import uuid4

CORRELATION_HEADER = "X-Correlation-Id"

_correlation_id: ContextVar[str | None] = ContextVar("correlation_id", default=None)


def get_correlation_id() -> str | None:
    """Current correlation id, or ``None`` outside a request."""
    return _correlation_id.get()


def set_correlation_id(value: str | None) -> None:
    """Bind the correlation id for the current context."""
    _correlation_id.set(value)


def new_correlation_id() -> str:
    """Generate a fresh id for requests that arrive without one."""
    return str(uuid4())
