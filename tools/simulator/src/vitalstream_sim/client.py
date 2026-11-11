"""Async ingest-gateway client with idempotent retries."""

from __future__ import annotations

import asyncio
import json
import random
import time
import uuid
from collections.abc import Awaitable, Callable, Sequence
from dataclasses import dataclass, field
from enum import StrEnum
from typing import Any

import httpx

from vitalstream_sim.logs import get_logger
from vitalstream_sim.payloads import Reading, build_device_batch, build_fhir_bundle, build_hl7_oru

log = get_logger()

RETRYABLE_STATUS = frozenset({429, 503})
PATH_BATCH = "/api/v1/vitals/batch"
PATH_FHIR = "/api/v1/fhir/Observation"
PATH_HL7 = "/api/v1/hl7"

Sleep = Callable[[float], Awaitable[None]]


class Transport(StrEnum):
    """Wire format used to deliver readings."""

    BATCH = "batch"
    FHIR = "fhir"
    HL7 = "hl7"


class IngestError(Exception):
    """The gateway refused the request in a way retrying cannot fix (4xx)."""

    def __init__(self, status: int, detail: str = "") -> None:
        super().__init__(f"ingest gateway answered {status}: {detail}")
        self.status = status
        self.detail = detail


class IngestUnavailableError(IngestError):
    """Retries were exhausted while the gateway kept answering 429/503 or was unreachable."""


@dataclass(frozen=True)
class Rejection:
    """One reading the gateway refused."""

    event_id: str | None
    reason: str


@dataclass
class SendResult:
    """Outcome of one delivered request."""

    accepted: int
    duplicates: int
    rejected: list[Rejection] = field(default_factory=list)
    latency_s: float = 0.0
    attempts: int = 1
    correlation_id: str = ""


class IngestClient:
    """Posts readings with ``X-Api-Key`` and ``X-Correlation-Id``.

    Retries 429 (honouring ``Retry-After``), 503 and connection errors with exponential backoff.
    The request body is built once, so every retry carries the same event ids and the gateway
    de-duplicates instead of double-ingesting.
    """

    def __init__(
        self,
        base_url: str,
        api_key: str,
        *,
        client: httpx.AsyncClient | None = None,
        max_retries: int = 5,
        backoff_base: float = 0.5,
        backoff_max: float = 30.0,
        timeout: float = 10.0,
        sleep: Sleep = asyncio.sleep,
        rng: random.Random | None = None,
    ) -> None:
        self._api_key = api_key
        self._http = client or httpx.AsyncClient(base_url=base_url, timeout=timeout)
        self._base_url = base_url.rstrip("/")
        self._max_retries = max_retries
        self._backoff_base = backoff_base
        self._backoff_max = backoff_max
        self._sleep = sleep
        self._rng = rng or random.Random()

    async def aclose(self) -> None:
        """Release pooled connections."""
        await self._http.aclose()

    async def send(self, transport: Transport, readings: Sequence[Reading]) -> SendResult:
        """Deliver ``readings`` in the chosen wire format."""
        if transport is Transport.FHIR:
            return await self.send_fhir(readings)
        if transport is Transport.HL7:
            return await self.send_hl7(readings)
        return await self.send_batch(readings)

    async def send_batch(self, readings: Sequence[Reading]) -> SendResult:
        """Device batch JSON (default transport)."""
        body = json.dumps(build_device_batch(readings)).encode()
        return self._parse_json(await self._post(PATH_BATCH, body, "application/json"))

    async def send_fhir(self, readings: Sequence[Reading]) -> SendResult:
        """FHIR R4 Bundle."""
        body = json.dumps(build_fhir_bundle(readings)).encode()
        return self._parse_json(await self._post(PATH_FHIR, body, "application/fhir+json"))

    async def send_hl7(self, readings: Sequence[Reading]) -> SendResult:
        """HL7 v2.5 ORU^R01; the answer is an ACK."""
        body = build_hl7_oru(readings).encode()
        sent = await self._post(PATH_HL7, body, "text/plain")
        return self._parse_ack(sent, total=len(readings))

    async def _post(self, path: str, body: bytes, content_type: str) -> _Sent:
        correlation_id = str(uuid.uuid4())
        headers = {
            "X-Api-Key": self._api_key,
            "X-Correlation-Id": correlation_id,
            "Content-Type": content_type,
        }
        last_status = 0
        for attempt in range(self._max_retries + 1):
            started = time.perf_counter()
            try:
                response = await self._http.post(path, content=body, headers=headers)
            except httpx.TransportError as error:
                last_status = 0
                delay = self._backoff(attempt, None)
                log.warning(
                    "ingest.retry", correlationId=correlation_id, reason=type(error).__name__,
                    attempt=attempt + 1, delaySeconds=round(delay, 3),
                )  # fmt: skip
            else:
                latency = time.perf_counter() - started
                if response.is_success:
                    return _Sent(response, latency, attempt + 1, correlation_id)
                last_status = response.status_code
                if response.status_code not in RETRYABLE_STATUS:
                    raise IngestError(response.status_code, response.text[:200])
                delay = self._backoff(attempt, response.headers.get("Retry-After"))
                log.warning(
                    "ingest.retry", correlationId=correlation_id, status=response.status_code,
                    attempt=attempt + 1, delaySeconds=round(delay, 3),
                )  # fmt: skip
            if attempt < self._max_retries:
                await self._sleep(delay)
        raise IngestUnavailableError(last_status, f"gave up after {self._max_retries + 1} attempts")

    def _backoff(self, attempt: int, retry_after: str | None) -> float:
        if retry_after is not None:
            try:
                return float(min(max(float(retry_after), 0.0), self._backoff_max))
            except ValueError:
                pass
        exponential = min(self._backoff_base * (2.0**attempt), self._backoff_max)
        jitter: float = self._rng.uniform(0.5, 1.0)
        return exponential * jitter

    @staticmethod
    def _parse_json(sent: _Sent) -> SendResult:
        data: dict[str, Any] = sent.response.json()
        rejected = [
            Rejection(item.get("eventId"), str(item.get("reason", "")))
            for item in data.get("rejected", [])
        ]
        return SendResult(
            accepted=int(data.get("accepted", 0)),
            duplicates=int(data.get("duplicates", 0)),
            rejected=rejected,
            latency_s=sent.latency,
            attempts=sent.attempts,
            correlation_id=sent.correlation_id,
        )

    @staticmethod
    def _parse_ack(sent: _Sent, total: int) -> SendResult:
        """HL7 answers with an ACK: ``MSA|AA`` all fine, ``MSA|AE`` plus one ``ERR`` per reject."""
        segments = [s.split("|") for s in sent.response.text.replace("\n", "\r").split("\r") if s]
        errors = [s for s in segments if s[0] == "ERR"]
        rejected = [Rejection(None, next((f for f in reversed(s) if f), "")) for s in errors]
        return SendResult(
            accepted=max(total - len(rejected), 0),
            duplicates=0,
            rejected=rejected,
            latency_s=sent.latency,
            attempts=sent.attempts,
            correlation_id=sent.correlation_id,
        )


@dataclass(frozen=True)
class _Sent:
    response: httpx.Response
    latency: float
    attempts: int
    correlation_id: str
