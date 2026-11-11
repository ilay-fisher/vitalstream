"""FastAPI application factory."""

from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from typing import Any

import structlog
from fastapi import FastAPI, HTTPException, Request
from fastapi.exceptions import RequestValidationError
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse

from copilot import __version__
from copilot.api import router
from copilot.config import Settings, get_settings
from copilot.correlation import CORRELATION_HEADER
from copilot.logging_setup import configure_logging
from copilot.mcp_server import build_mcp
from copilot.middleware import CorrelationMiddleware
from copilot.security import BearerGuard
from copilot.services import Services, build_services

log = structlog.get_logger()
PROBLEM_JSON = "application/problem+json"


def _problem(status: int, title: str, detail: str, **extra: Any) -> JSONResponse:
    """RFC 9457 problem details response."""
    body = {"type": "about:blank", "title": title, "status": status, "detail": detail, **extra}
    return JSONResponse(body, status_code=status, media_type=PROBLEM_JSON)


def create_app(settings: Settings | None = None, services: Services | None = None) -> FastAPI:
    """Build the app. Tests inject ``services``; production builds them in the lifespan."""
    settings = settings or get_settings()
    configure_logging(settings.service_name, settings.log_level)
    mcp = build_mcp(lambda: app.state.services.tools, settings.mcp_host_list)

    @asynccontextmanager
    async def lifespan(_: FastAPI) -> AsyncIterator[None]:
        owned = services is None
        if owned:
            app.state.services = await build_services(settings)
        try:
            async with mcp.session_manager.run():
                log.info("copilot_started", port=settings.port)
                yield
        finally:
            if owned:
                await app.state.services.aclose()

    app = FastAPI(
        title="VitalStream Copilot",
        version=__version__,
        description="Clinical decision-support copilot: RAG over guidelines, agent chat, MCP.",
        lifespan=lifespan,
    )
    if services is not None:
        app.state.services = services
    app.state.admin_token = settings.copilot_admin_token.get_secret_value()
    app.state.pseudonym_key = settings.log_pseudonym_key.get_secret_value()
    app.include_router(router)
    app.router.routes.extend(mcp.streamable_http_app().routes)

    @app.exception_handler(RequestValidationError)
    async def validation_handler(request: Request, exc: RequestValidationError) -> JSONResponse:
        errors = [
            {"field": ".".join(str(p) for p in e["loc"][1:]), "message": e["msg"]}
            for e in exc.errors()
        ]
        return _problem(422, "Validation failed", "Request validation failed", errors=errors)

    @app.exception_handler(HTTPException)
    async def http_handler(request: Request, exc: HTTPException) -> JSONResponse:
        response = _problem(exc.status_code, str(exc.detail), str(exc.detail))
        response.headers.update(exc.headers or {})
        return response

    app.add_middleware(BearerGuard, prefix="/mcp", token=settings.mcp_token.get_secret_value())
    app.add_middleware(CorrelationMiddleware)
    app.add_middleware(
        CORSMiddleware,
        allow_origins=settings.cors_origin_list,
        allow_methods=["*"],
        allow_headers=["*"],
        expose_headers=[CORRELATION_HEADER],
    )
    return app
