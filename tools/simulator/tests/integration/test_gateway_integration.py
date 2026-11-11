"""End-to-end against a real ingest-gateway (built from services/ingest-gateway) plus Redis, Kafka.

Run with ``uv run pytest -m integration -o addopts=""`` (needs Docker; see CONTRIBUTING section 7
for the Colima/Testcontainers environment). Containers are named ``simdash-*`` and use host port
25081, inside the simulator/dashboard 25xxx block.
"""

from __future__ import annotations

import os
import subprocess
import time
import uuid
from collections.abc import Iterator
from datetime import UTC, datetime, timedelta
from pathlib import Path

import httpx
import pytest
from testcontainers.core.container import DockerContainer
from testcontainers.core.network import Network
from testcontainers.core.waiting_utils import wait_for_logs

from vitalstream_sim.client import IngestClient, Transport
from vitalstream_sim.generator import VitalsGenerator
from vitalstream_sim.patients import build_patients
from vitalstream_sim.payloads import Reading
from vitalstream_sim.scenarios import Scenario

pytestmark = pytest.mark.integration

GATEWAY_DIR = Path(__file__).resolve().parents[4] / "services" / "ingest-gateway"
GATEWAY_IMAGE = os.environ.get("GATEWAY_IMAGE", "simdash/ingest-gateway:test")
HOST_PORT = 25081
BASE_URL = f"http://127.0.0.1:{HOST_PORT}"
API_KEY = "dev-ingest-key"


def _ensure_image() -> None:
    present = subprocess.run(["docker", "image", "inspect", GATEWAY_IMAGE], capture_output=True)
    if present.returncode != 0:
        subprocess.run(["docker", "build", "-t", GATEWAY_IMAGE, str(GATEWAY_DIR)], check=True)


def _kafka(network: Network, suffix: str) -> DockerContainer:
    return (
        DockerContainer("apache/kafka:3.8.1")
        .with_name(f"simdash-kafka-{suffix}")
        .with_network(network)
        .with_network_aliases("kafka")
        .with_env("CLUSTER_ID", "MkU3OEVBNTcwNTJENDM2Qk")
        .with_env("KAFKA_NODE_ID", "1")
        .with_env("KAFKA_PROCESS_ROLES", "broker,controller")
        .with_env("KAFKA_LISTENERS", "INTERNAL://:29092,CONTROLLER://:9093")
        .with_env("KAFKA_ADVERTISED_LISTENERS", "INTERNAL://kafka:29092")
        .with_env("KAFKA_LISTENER_SECURITY_PROTOCOL_MAP", "INTERNAL:PLAINTEXT,CONTROLLER:PLAINTEXT")
        .with_env("KAFKA_INTER_BROKER_LISTENER_NAME", "INTERNAL")
        .with_env("KAFKA_CONTROLLER_LISTENER_NAMES", "CONTROLLER")
        .with_env("KAFKA_CONTROLLER_QUORUM_VOTERS", "1@kafka:9093")
        .with_env("KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR", "1")
        .with_env("KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR", "1")
        .with_env("KAFKA_TRANSACTION_STATE_LOG_MIN_ISR", "1")
        .with_env("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "true")
        .with_env("KAFKA_HEAP_OPTS", "-Xms256m -Xmx256m")
    )


@pytest.fixture(scope="module")
def gateway_url() -> Iterator[str]:
    _ensure_image()
    suffix = uuid.uuid4().hex[:6]
    with Network() as network:
        redis = (
            DockerContainer("redis:7-alpine")
            .with_name(f"simdash-redis-{suffix}")
            .with_network(network)
            .with_network_aliases("redis")
        )
        kafka = _kafka(network, suffix)
        gateway = (
            DockerContainer(GATEWAY_IMAGE)
            .with_name(f"simdash-gateway-{suffix}")
            .with_network(network)
            .with_bind_ports(8081, HOST_PORT)
            .with_env("KAFKA_BOOTSTRAP_SERVERS", "kafka:29092")
            .with_env("REDIS_URL", "redis://redis:6379")
            .with_env("INGEST_API_KEY", API_KEY)
            .with_env(
                "ALLOWED_HOSTS", f"localhost,127.0.0.1,127.0.0.1:{HOST_PORT},localhost:{HOST_PORT}"
            )
        )
        with redis, kafka:
            wait_for_logs(redis, "Ready to accept connections", timeout=60)
            wait_for_logs(kafka, "Kafka Server started", timeout=120)
            with gateway:
                deadline = time.monotonic() + 180
                while time.monotonic() < deadline:
                    try:
                        health = httpx.get(f"{BASE_URL}/actuator/health", timeout=2)
                        if health.status_code == 200:
                            break
                    except httpx.HTTPError:
                        pass
                    time.sleep(2)
                else:
                    raise TimeoutError(gateway.get_logs())
                yield BASE_URL


def readings_for(patient_index: int, scenario: Scenario, tick: int, at: datetime) -> list[Reading]:
    patient = build_patients()[patient_index]
    gen = VitalsGenerator(patient, scenario)
    values = gen.snapshot(tick * 10.0, tick, at)
    return [Reading(patient.id, patient.device_id, m, v, at) for m, v in values.items()]


async def test_batch_is_accepted_then_deduplicated_on_resend(gateway_url: str) -> None:
    at = datetime.now(UTC).replace(microsecond=0)
    readings = readings_for(0, Scenario.SEPSIS, 3, at)
    client = IngestClient(gateway_url, API_KEY)
    first = await client.send_batch(readings)
    again = await client.send_batch(readings)
    await client.aclose()
    assert (first.accepted, first.duplicates, first.rejected) == (6, 0, [])
    assert (again.accepted, again.duplicates) == (0, 6)


async def test_raw_response_is_202_and_echoes_correlation_id(gateway_url: str) -> None:
    from vitalstream_sim.payloads import build_device_batch

    at = datetime.now(UTC).replace(microsecond=0)
    body = build_device_batch(readings_for(1, Scenario.STABLE, 1, at))
    async with httpx.AsyncClient(base_url=gateway_url) as http:
        response = await http.post(
            "/api/v1/vitals/batch",
            json=body,
            headers={"X-Api-Key": API_KEY, "X-Correlation-Id": "sim-integration-1"},
        )
        unauthorised = await http.post("/api/v1/vitals/batch", json=body)
    assert response.status_code == 202
    assert response.headers["x-correlation-id"] == "sim-integration-1"
    assert unauthorised.status_code == 401


async def test_fhir_and_hl7_transports_are_accepted(gateway_url: str) -> None:
    at = datetime.now(UTC).replace(microsecond=0)
    client = IngestClient(gateway_url, API_KEY)
    fhir = await client.send(Transport.FHIR, readings_for(2, Scenario.STABLE, 1, at))
    hl7 = await client.send(Transport.HL7, readings_for(3, Scenario.STABLE, 1, at))
    hl7_again = await client.send(Transport.HL7, readings_for(3, Scenario.STABLE, 1, at))
    await client.aclose()
    assert (fhir.accepted, fhir.rejected) == (6, [])
    assert (hl7.accepted, hl7.rejected) == (6, [])
    assert hl7_again.accepted == 6  # HL7 ACKs do not distinguish duplicates (AA either way)


async def test_sensor_fault_readings_are_rejected(gateway_url: str) -> None:
    now = datetime.now(UTC).replace(microsecond=0)
    client = IngestClient(gateway_url, API_KEY)
    reasons: list[str] = []
    accepted = 0
    for tick in range(10):
        at = now - timedelta(seconds=tick)
        result = await client.send_batch(readings_for(4, Scenario.SENSOR_FAULT, tick, at))
        accepted += result.accepted
        reasons += [r.reason for r in result.rejected]
    await client.aclose()
    assert reasons
    assert all(reason.startswith("OUT_OF_RANGE") for reason in reasons)
    assert accepted > 0
