"""REST endpoints: chat (SSE), documents, hybrid-search debug, health and metrics."""

import json
from collections.abc import AsyncIterator
from typing import Annotated, Any

import structlog
from fastapi import APIRouter, Depends, HTTPException, Query, Request, Response
from fastapi.responses import StreamingResponse
from prometheus_client import CONTENT_TYPE_LATEST, generate_latest
from pydantic import BaseModel, Field

from copilot.correlation import get_correlation_id
from copilot.llm.types import Message
from copilot.metrics import CHAT_REQUESTS
from copilot.pseudonym import patient_ref
from copilot.security import bearer_matches
from copilot.services import Services

log = structlog.get_logger()
router = APIRouter()

PATIENT_ID_PATTERN = r"^P-\d{4,}$"


class ChatRequest(BaseModel):
    """Body of `POST /api/v1/chat`."""

    sessionId: str = Field(min_length=1, max_length=128, pattern=r"^[\w.\-:]+$")
    patientId: str | None = Field(default=None, pattern=PATIENT_ID_PATTERN)
    message: str = Field(min_length=1, max_length=4000)


class DocumentIn(BaseModel):
    """One markdown document to ingest."""

    title: str | None = Field(default=None, max_length=300)
    source: str = Field(default="api", max_length=300)
    content: str = Field(min_length=1, max_length=500_000)


class DocumentsIn(BaseModel):
    """Body of `POST /api/v1/documents`."""

    documents: list[DocumentIn] = Field(min_length=1, max_length=50)


def format_sse(event: str, data: dict[str, Any]) -> str:
    """Serialise one server-sent event (`event:` + single-line JSON `data:`)."""
    return f"event: {event}\ndata: {json.dumps(data, separators=(',', ':'))}\n\n"


def _services(request: Request) -> Services:
    services: Services = request.app.state.services
    return services


def require_admin(request: Request) -> None:
    """Dependency: `Authorization: Bearer COPILOT_ADMIN_TOKEN` (constant-time compare)."""
    if not bearer_matches(request.headers.get("authorization"), request.app.state.admin_token):
        raise HTTPException(
            status_code=401,
            detail="Missing or invalid bearer token",
            headers={"WWW-Authenticate": 'Bearer realm="copilot"'},
        )


async def _chat_stream(
    services: Services, body: ChatRequest, pseudonym_key: str
) -> AsyncIterator[str]:
    history = services.memory.get(body.sessionId)
    correlation_id = get_correlation_id()
    log.info(
        "chat_started",
        sessionId=body.sessionId,
        patientRef=patient_ref(body.patientId, pseudonym_key) if body.patientId else None,
        historyMessages=len(history),
    )
    try:
        async for event in services.agent.run(history, body.message, body.patientId):
            data = dict(event.data)
            if event.type == "done":
                services.memory.append(
                    body.sessionId,
                    [Message("user", body.message), Message("assistant", data["text"])],
                )
                data |= {"sessionId": body.sessionId, "correlationId": correlation_id}
                CHAT_REQUESTS.labels(status="ok").inc()
            yield format_sse(event.type, data)
    except Exception:
        log.exception("chat_failed", sessionId=body.sessionId)
        CHAT_REQUESTS.labels(status="error").inc()
        yield format_sse(
            "done",
            {
                "text": "Sorry, something went wrong while answering. Please try again.",
                "error": True,
                "sessionId": body.sessionId,
                "correlationId": correlation_id,
            },
        )


@router.post("/api/v1/chat", summary="Chat with the clinical copilot (SSE)")
async def chat(body: ChatRequest, request: Request) -> StreamingResponse:
    """Stream the copilot's answer as SSE events.

    Events: `token` (answer text), `tool_call` (tool started/finished), `citation`
    (guideline passage backing the answer) and a final `done` (full text, usage, ids).
    Conversation memory is per `sessionId` and bounded by TTL and size.
    """
    return StreamingResponse(
        _chat_stream(_services(request), body, request.app.state.pseudonym_key),
        media_type="text/event-stream",
        headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"},
    )


@router.post(
    "/api/v1/documents",
    status_code=201,
    summary="Ingest markdown guidelines (admin token)",
    dependencies=[Depends(require_admin)],
)
async def ingest_documents(body: DocumentsIn, request: Request) -> dict[str, Any]:
    """Chunk, embed and index markdown documents; requires the admin bearer token.

    Uploaded documents are stored with provenance `api-upload` (the bundled corpus is `curated`).
    """
    services = _services(request)
    ingested = []
    for document in body.documents:
        try:
            result = await services.ingest.ingest(
                title=document.title,
                content=document.content,
                source=document.source,
                provenance="api-upload",
            )
        except ValueError as exc:
            raise HTTPException(status_code=422, detail=str(exc)) from exc
        ingested.append(
            {
                "documentId": result.document_id,
                "title": result.title,
                "chunks": result.chunks,
                "provenance": "api-upload",
            }
        )
    return {"documents": ingested}


@router.get("/api/v1/documents/search", summary="Hybrid search (debug)")
async def search_documents(
    request: Request,
    q: Annotated[str, Query(min_length=1, max_length=500)],
    k: Annotated[int, Query(ge=1, le=50)] = 5,
) -> dict[str, Any]:
    """Run the hybrid BM25 + vector search and show each hit's rank on both sides."""
    hits = await _services(request).retriever.search(q, k)
    return {
        "query": q,
        "k": k,
        "results": [
            {
                "chunkId": h.chunk_id,
                "documentId": h.document_id,
                "title": h.title,
                "content": h.content,
                "score": round(h.score, 6),
                "vectorRank": h.vector_rank,
                "bm25Rank": h.bm25_rank,
                "provenance": h.provenance,
            }
            for h in hits
        ],
    }


@router.get("/health", summary="Liveness/readiness")
async def health(request: Request, response: Response) -> dict[str, Any]:
    """`UP` when critical dependencies answer; reports each dependency's state."""
    services = _services(request)
    components: dict[str, str] = {}
    for name, check in services.health_checks.items():
        try:
            components[name] = "UP" if await check() else "DOWN"
        except Exception:
            components[name] = "DOWN"
    critical_down = any(components.get(n) == "DOWN" for n in services.critical_checks)
    if critical_down:
        response.status_code = 503
    status = "DOWN" if critical_down else ("DEGRADED" if "DOWN" in components.values() else "UP")
    return {"status": status, "llmProvider": services.llm_provider, "components": components}


@router.get("/metrics", include_in_schema=False)
async def metrics() -> Response:
    """Prometheus exposition."""
    return Response(generate_latest(), media_type=CONTENT_TYPE_LATEST)
