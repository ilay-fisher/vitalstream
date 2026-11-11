"""Bearer-token checks (constant-time) shared by the documents endpoint and /mcp."""

import hmac

from starlette.responses import JSONResponse
from starlette.types import ASGIApp, Receive, Scope, Send


def bearer_matches(header: str | None, expected: str) -> bool:
    """True when ``header`` is `Bearer <expected>`; compares in constant time."""
    if not header or not expected:
        return False
    scheme, _, token = header.partition(" ")
    ok_scheme = hmac.compare_digest(scheme.lower().encode(), b"bearer")
    ok_token = hmac.compare_digest(token.strip().encode(), expected.encode())
    return ok_scheme and ok_token


def unauthorized() -> JSONResponse:
    """401 problem+json with a Bearer challenge (never echoes the supplied token)."""
    return JSONResponse(
        {
            "type": "about:blank",
            "title": "Unauthorized",
            "status": 401,
            "detail": "Missing or invalid bearer token",
        },
        status_code=401,
        media_type="application/problem+json",
        headers={"WWW-Authenticate": 'Bearer realm="copilot"'},
    )


class BearerGuard:
    """ASGI middleware requiring a bearer token for requests under ``prefix``."""

    def __init__(self, app: ASGIApp, prefix: str, token: str) -> None:
        self.app = app
        self._prefix = prefix
        self._token = token

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] == "http" and scope["path"].startswith(self._prefix):
            header = next(
                (v.decode("latin-1") for k, v in scope["headers"] if k == b"authorization"), None
            )
            if not bearer_matches(header, self._token):
                await unauthorized()(scope, receive, send)
                return
        await self.app(scope, receive, send)
