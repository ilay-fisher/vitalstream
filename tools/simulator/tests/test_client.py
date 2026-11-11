from __future__ import annotations

import httpx
import pytest
import respx

from tests.test_payloads import make_readings
from vitalstream_sim.client import IngestClient, IngestError, IngestUnavailableError

BASE = "http://gw.test:8081"
OK = {"accepted": 6, "duplicates": 0, "rejected": []}


class Sleeper:
    def __init__(self) -> None:
        self.delays: list[float] = []

    async def __call__(self, delay: float) -> None:
        self.delays.append(delay)


@pytest.fixture
def sleeper() -> Sleeper:
    return Sleeper()


def make_client(sleeper: Sleeper, **kwargs: float) -> IngestClient:
    return IngestClient(BASE, "k-123", sleep=sleeper, backoff_base=0.5, backoff_max=8, **kwargs)  # type: ignore[arg-type]


@respx.mock
async def test_batch_sends_auth_and_correlation_headers(sleeper: Sleeper) -> None:
    route = respx.post(f"{BASE}/api/v1/vitals/batch").respond(202, json=OK)
    client = make_client(sleeper)
    result = await client.send_batch(make_readings(6))
    request = route.calls.last.request
    assert request.headers["x-api-key"] == "k-123"
    assert request.headers["x-correlation-id"] == result.correlation_id
    assert request.headers["content-type"].startswith("application/json")
    assert (result.accepted, result.duplicates, result.rejected, result.attempts) == (6, 0, [], 1)
    assert result.latency_s >= 0
    await client.aclose()


@respx.mock
async def test_fhir_and_hl7_routes_and_content_types(sleeper: Sleeper) -> None:
    fhir = respx.post(f"{BASE}/api/v1/fhir/Observation").respond(202, json=OK)
    ack = "MSH|^~\\&|VITALSTREAM|HOSP|X|Y|20261111||ACK|1|P|2.5\rMSA|AA|SIM1\r"
    hl7 = respx.post(f"{BASE}/api/v1/hl7").respond(202, text=ack)
    client = make_client(sleeper)
    await client.send_fhir(make_readings(6))
    result = await client.send_hl7(make_readings(6))
    assert fhir.calls.last.request.headers["content-type"].startswith("application/fhir+json")
    assert hl7.calls.last.request.headers["content-type"].startswith("text/plain")
    assert hl7.calls.last.request.headers["x-api-key"] == "k-123"
    assert hl7.calls.last.request.headers["x-correlation-id"]
    assert result.accepted == 6
    await client.aclose()


@respx.mock
async def test_hl7_ae_ack_counts_rejections(sleeper: Sleeper) -> None:
    ack = (
        "MSH|^~\\&|VITALSTREAM|HOSP|X|Y|20261111||ACK|1|P|2.5\rMSA|AE|SIM1\r"
        "ERR|||207^OBX 2: OUT_OF_RANGE\rERR|||207^OBX 5: OUT_OF_RANGE\r"
    )
    respx.post(f"{BASE}/api/v1/hl7").respond(202, text=ack)
    client = make_client(sleeper)
    result = await client.send_hl7(make_readings(6))
    assert result.accepted == 4
    assert len(result.rejected) == 2
    assert "OUT_OF_RANGE" in result.rejected[0].reason
    await client.aclose()


@respx.mock
async def test_retries_429_honouring_retry_after(sleeper: Sleeper) -> None:
    route = respx.post(f"{BASE}/api/v1/vitals/batch").mock(
        side_effect=[
            httpx.Response(429, headers={"Retry-After": "3"}),
            httpx.Response(429, headers={"Retry-After": "1"}),
            httpx.Response(202, json=OK),
        ]
    )
    client = make_client(sleeper)
    result = await client.send_batch(make_readings(6))
    assert route.call_count == 3
    assert result.attempts == 3
    assert sleeper.delays == [3.0, 1.0]
    ids = {c.request.headers["x-correlation-id"] for c in route.calls}
    assert len(ids) == 1
    await client.aclose()


@respx.mock
async def test_retries_503_with_exponential_backoff(sleeper: Sleeper) -> None:
    route = respx.post(f"{BASE}/api/v1/vitals/batch").mock(
        side_effect=[httpx.Response(503), httpx.Response(503), httpx.Response(202, json=OK)]
    )
    client = make_client(sleeper)
    await client.send_batch(make_readings(6))
    assert route.call_count == 3
    first, second = sleeper.delays
    assert 0.25 <= first <= 0.5
    assert 0.5 <= second <= 1.0
    await client.aclose()


@respx.mock
async def test_retry_after_is_capped(sleeper: Sleeper) -> None:
    respx.post(f"{BASE}/api/v1/vitals/batch").mock(
        side_effect=[
            httpx.Response(429, headers={"Retry-After": "999"}),
            httpx.Response(202, json=OK),
        ]
    )
    client = make_client(sleeper)
    await client.send_batch(make_readings(6))
    assert sleeper.delays == [8.0]
    await client.aclose()


@respx.mock
async def test_gives_up_after_max_retries(sleeper: Sleeper) -> None:
    route = respx.post(f"{BASE}/api/v1/vitals/batch").respond(503)
    client = make_client(sleeper, max_retries=2)
    with pytest.raises(IngestUnavailableError):
        await client.send_batch(make_readings(6))
    assert route.call_count == 3
    await client.aclose()


@respx.mock
async def test_retries_transport_errors(sleeper: Sleeper) -> None:
    route = respx.post(f"{BASE}/api/v1/vitals/batch").mock(
        side_effect=[httpx.ConnectError("boom"), httpx.Response(202, json=OK)]
    )
    client = make_client(sleeper)
    result = await client.send_batch(make_readings(6))
    assert result.attempts == 2
    assert route.call_count == 2
    await client.aclose()


@respx.mock
@pytest.mark.parametrize("status", [400, 401, 415])
async def test_client_errors_are_not_retried(sleeper: Sleeper, status: int) -> None:
    route = respx.post(f"{BASE}/api/v1/vitals/batch").respond(status, json={"title": "nope"})
    client = make_client(sleeper)
    with pytest.raises(IngestError) as info:
        await client.send_batch(make_readings(6))
    assert info.value.status == status
    assert route.call_count == 1
    assert sleeper.delays == []
    await client.aclose()


@respx.mock
async def test_rejections_are_parsed(sleeper: Sleeper) -> None:
    body = {
        "accepted": 5,
        "duplicates": 0,
        "rejected": [{"eventId": "e1", "reason": "OUT_OF_RANGE"}],
    }
    respx.post(f"{BASE}/api/v1/vitals/batch").respond(202, json=body)
    client = make_client(sleeper)
    result = await client.send_batch(make_readings(6))
    assert result.rejected[0].event_id == "e1"
    assert result.rejected[0].reason == "OUT_OF_RANGE"
    await client.aclose()


@respx.mock
async def test_send_dispatches_on_transport(sleeper: Sleeper) -> None:
    from vitalstream_sim.client import Transport

    respx.post(f"{BASE}/api/v1/vitals/batch").respond(202, json=OK)
    respx.post(f"{BASE}/api/v1/fhir/Observation").respond(202, json=OK)
    client = make_client(sleeper)
    assert (await client.send(Transport.BATCH, make_readings(6))).accepted == 6
    assert (await client.send(Transport.FHIR, make_readings(6))).accepted == 6
    await client.aclose()
