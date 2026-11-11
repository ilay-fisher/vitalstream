"""One end-to-end run of the real job (availableNow) against Kafka, Cassandra and Redis."""

from __future__ import annotations

import json
import os
import subprocess
import sys
import time
from collections.abc import Iterator
from pathlib import Path
from typing import Any

import pytest

pytestmark = pytest.mark.integration

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))

from demo_stream import default_base, generate_events, produce  # noqa: E402


def _free_port() -> int:
    import socket

    with socket.socket() as sock:
        sock.bind(("", 0))
        return int(sock.getsockname()[1])


@pytest.fixture(scope="module")
def infra() -> Iterator[dict[str, Any]]:
    from testcontainers.core.container import DockerContainer
    from testcontainers.core.waiting_utils import wait_for_logs
    from testcontainers.redis import RedisContainer

    cassandra = (
        DockerContainer("cassandra:4.1")
        .with_exposed_ports(9042)
        .with_env("MAX_HEAP_SIZE", "768M")
        .with_env("HEAP_NEWSIZE", "128M")
    )
    port = _free_port()
    kafka = DockerContainer("apache/kafka:3.9.0").with_bind_ports(9092, port)
    for key, value in {
        "KAFKA_NODE_ID": "1",
        "KAFKA_PROCESS_ROLES": "broker,controller",
        "KAFKA_LISTENERS": "PLAINTEXT://:9092,CONTROLLER://:9093",
        "KAFKA_ADVERTISED_LISTENERS": f"PLAINTEXT://{kafka.get_container_host_ip()}:{port}",
        "KAFKA_CONTROLLER_LISTENER_NAMES": "CONTROLLER",
        "KAFKA_LISTENER_SECURITY_PROTOCOL_MAP": "CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT",
        "KAFKA_CONTROLLER_QUORUM_VOTERS": "1@localhost:9093",
        "KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR": "1",
        "KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR": "1",
        "KAFKA_TRANSACTION_STATE_LOG_MIN_ISR": "1",
        "KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS": "0",
    }.items():
        kafka.with_env(key, value)
    with kafka, RedisContainer("redis:7.4-alpine") as redis_c, cassandra:
        wait_for_logs(kafka, "Kafka Server started", timeout=120)
        wait_for_logs(cassandra, "Starting listening for CQL clients", timeout=240)
        yield {
            "kafka": f"{kafka.get_container_host_ip()}:{port}",
            "redis_host": redis_c.get_container_host_ip(),
            "redis_port": int(redis_c.get_exposed_port(6379)),
            "cass_host": cassandra.get_container_host_ip(),
            "cass_port": int(cassandra.get_exposed_port(9042)),
        }


def _cassandra_session(host: str, port: int) -> Any:
    from cassandra.cluster import Cluster

    deadline = time.time() + 120
    while True:
        try:
            return Cluster([host], port=port, protocol_version=4).connect()
        except Exception:
            if time.time() > deadline:
                raise
            time.sleep(3)


def _create_topics(bootstrap: str) -> None:
    from confluent_kafka.admin import AdminClient, NewTopic

    admin = AdminClient({"bootstrap.servers": bootstrap})
    for future in admin.create_topics(
        [NewTopic("vitals.raw", 3, 1), NewTopic("alerts.detected", 1, 1)]
    ).values():
        future.result()


def _consume_alerts(bootstrap: str) -> list[tuple[dict[str, Any], dict[str, str], str]]:
    from confluent_kafka import Consumer

    consumer = Consumer(
        {
            "bootstrap.servers": bootstrap,
            "group.id": "it-check",
            "auto.offset.reset": "earliest",
            "enable.auto.commit": False,
        }
    )
    consumer.subscribe(["alerts.detected"])
    out: list[tuple[dict[str, Any], dict[str, str], str]] = []
    idle = 0
    while idle < 5:
        msg = consumer.poll(2.0)
        if msg is None or msg.error():
            idle += 1
            continue
        idle = 0
        headers = {k: v.decode() for k, v in (msg.headers() or [])}
        out.append((json.loads(msg.value()), headers, msg.key().decode()))
    consumer.close()
    return out


def test_pipeline_end_to_end(infra: dict[str, Any], tmp_path: Path) -> None:
    import redis

    session = _cassandra_session(infra["cass_host"], infra["cass_port"])
    for statement in (ROOT / "tests/integration/schema.cql").read_text().split(";"):
        if statement.strip():
            session.execute(statement)
    _create_topics(infra["kafka"])
    base = default_base(18)
    sent = produce(
        infra["kafka"], "vitals.raw", generate_events(minutes=18, base=base, poison=True)
    )
    valid = 18 * 12 * 12  # minutes * samples per minute * (2 patients * 6 metrics)
    assert sent == valid + 1  # plus one far-future poison event that must be dropped

    archive = tmp_path / "archive"
    env = {
        **os.environ,
        "KAFKA_BOOTSTRAP_SERVERS": infra["kafka"],
        "REDIS_URL": f"redis://{infra['redis_host']}:{infra['redis_port']}",
        "CASSANDRA_CONTACT_POINTS": f"{infra['cass_host']}:{infra['cass_port']}",
        "TRIGGER_MODE": "availableNow",
        "CHECKPOINT_DIR": str(tmp_path / "ckpt"),
        "ANOMALY_MODEL_DIR": str(tmp_path / "model"),
        "ARCHIVE_URI": archive.as_uri(),
        "METRICS_PORT": "0",
        "SPARK_MASTER": "local[2]",
        "LOG_LEVEL": "INFO",
    }
    proc = subprocess.run(
        [sys.executable, "-m", "stream_processor.main"],
        env=env,
        capture_output=True,
        text=True,
        timeout=900,
        check=False,
    )
    assert proc.returncode == 0, proc.stdout[-3000:] + proc.stderr[-3000:]

    raw = list(session.execute("SELECT patient_id, metric FROM vitalstream.vitals_raw"))
    assert len(raw) == valid
    minutes = list(
        session.execute("SELECT patient_id, metric, count FROM vitalstream.vitals_by_minute")
    )
    assert {r.patient_id for r in minutes} == {"P-0001", "P-0002"}
    assert sum(r.count for r in minutes) == valid

    r = redis.Redis(host=infra["redis_host"], port=infra["redis_port"], decode_responses=True)
    latest = r.hgetall("patient:P-0002:latest")
    assert set(latest) == {
        "HEART_RATE",
        "SPO2",
        "RESP_RATE",
        "SYSTOLIC_BP",
        "DIASTOLIC_BP",
        "TEMPERATURE",
    }
    assert json.loads(latest["HEART_RATE"])["unit"] == "/min"
    assert r.hget("ewma:P-0002:HEART_RATE", "n") is not None

    alerts = _consume_alerts(infra["kafka"])
    assert alerts, "expected alerts for the deteriorating patient"
    ids = [a["alertId"] for a, _, _ in alerts]
    assert len(ids) == len(set(ids)), "alertIds must be unique (re-emission suppressed)"
    types = {(a["patientId"], a["type"]) for a, _, _ in alerts}
    for expected in ("THRESHOLD", "NEWS2", "TREND_ZSCORE"):
        assert ("P-0002", expected) in types
    stable = {a["type"] for a, _, _ in alerts if a["patientId"] == "P-0001"}
    assert not stable & {"THRESHOLD", "NEWS2"}
    # A 3-sigma rule fires on ~0.3 % of Gaussian noise by design, so the stable patient may see
    # a stray TREND_ZSCORE alert; only THRESHOLD and NEWS2 must stay silent for it.
    for alert, headers, key in alerts:
        assert key == alert["patientId"]
        assert headers["schemaVersion"] == "1"
        assert headers["source"] == "stream-processor"
        assert headers["correlationId"]

    parquet_dirs = sorted(p.relative_to(archive).as_posix() for p in archive.glob("dt=*/hour=*"))
    assert parquet_dirs and parquet_dirs[0].startswith(f"dt={base:%Y-%m-%d}/hour=")
    assert "batch progress" in proc.stdout
    assert "event dropped by event-time guard" in proc.stdout
    assert "P-0001" not in "".join(
        line for line in proc.stdout.splitlines() if "alert raised" in line
    )
