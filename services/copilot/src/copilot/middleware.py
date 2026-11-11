"""Pure-ASGI correlation middleware (safe for streaming responses, unlike BaseHTTPMiddleware)."""

import re
import time

import structlog
from starlette.types import ASGIApp, Message, Receive, Scope, Send

from copilot.correlation import CORRELATION_HEADER, new_correlation_id, set_correlation_id

log = structlog.get_logger()
_SAFE_ID = re.compile(r"^[\w.\-:]{1,128}$")
_QUIET_PATHS = frozenset({"/health", "/metrics"})
_HEADER_LOWER = CORRELATION_HEADER.lower().encode()


class CorrelationMiddleware:
    """Reads or generates `X-Correlation-Id`, binds it for logs/outbound calls and echoes it."""

    def __init__(self, app: ASGIApp) -> None:
        self.app = app

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return
        incoming = next(
            (v.decode("latin-1") for k, v in scope["headers"] if k == _HEADER_LOWER), None
        )
        correlation_id = incoming if incoming and _SAFE_ID.match(incoming) else new_correlation_id()
        set_correlation_id(correlation_id)
        started = time.perf_counter()
        status = 500

        async def send_with_header(message: Message) -> None:
            nonlocal status
            if message["type"] == "http.response.start":
                status = message["status"]
                headers = [
                    (k, v) for k, v in message.get("headers", []) if k.lower() != _HEADER_LOWER
                ]
                headers.append((_HEADER_LOWER, correlation_id.encode("latin-1")))
                message = {**message, "headers": headers}
            await send(message)

        try:
            await self.app(scope, receive, send_with_header)
        finally:
            if scope["path"] not in _QUIET_PATHS:
                log.info(
                    "request_completed",
                    method=scope["method"],
                    path=scope["path"],
                    status=status,
                    durationMs=round((time.perf_counter() - started) * 1000, 1),
                )
            set_correlation_id(None)
