"""Tail a Kafka topic from "now" so a test only sees records produced during its own run."""

from __future__ import annotations

import json
import threading
import time
from collections.abc import Callable
from dataclasses import dataclass
from typing import Any, Protocol

from vitalstream_e2e.polling import poll_until


@dataclass(frozen=True)
class Record:
    """A decoded Kafka record (JSON value, string key, string headers)."""

    topic: str
    key: str | None
    value: dict[str, Any]
    headers: dict[str, str]


class ConsumerLike(Protocol):
    """The slice of confluent_kafka.Consumer used here, so tests can fake it."""

    def poll(self, timeout: float) -> Any: ...

    def close(self) -> None: ...


class KafkaTail:
    """Poll a consumer on a daemon thread and keep every decodable JSON record."""

    def __init__(self, consumer: ConsumerLike, topic: str) -> None:
        self._consumer = consumer
        self._topic = topic
        self._records: list[Record] = []
        self._lock = threading.Lock()
        self._stopped = threading.Event()
        self._thread = threading.Thread(target=self._run, daemon=True)

    def start(self) -> None:
        self._thread.start()

    def stop(self) -> None:
        self._stopped.set()
        self._thread.join(timeout=5)
        self._consumer.close()

    @property
    def records(self) -> list[Record]:
        with self._lock:
            return list(self._records)

    def wait_for(
        self,
        predicate: Callable[[Record], bool],
        *,
        timeout: float,
        description: str,
        interval: float = 0.25,
    ) -> Record:
        """Block until a record matching ``predicate`` arrived."""
        return poll_until(
            lambda: next((r for r in self.records if predicate(r)), None),
            timeout=timeout,
            interval=interval,
            description=f"{description} on {self._topic}",
        )

    def _run(self) -> None:
        while not self._stopped.is_set():
            message = self._consumer.poll(0.2)
            if message is None:
                time.sleep(0.01)
                continue
            record = self._decode(message)
            if record is not None:
                with self._lock:
                    self._records.append(record)

    def _decode(self, message: Any) -> Record | None:
        if message.error() or message.value() is None:
            return None
        try:
            value = json.loads(message.value())
        except ValueError:
            return None
        if not isinstance(value, dict):
            return None
        key = message.key()
        headers = {k: v.decode() for k, v in (message.headers() or []) if v is not None}
        return Record(self._topic, key.decode() if key else None, value, headers)


def tail_topic(bootstrap: str, topic: str) -> KafkaTail:  # pragma: no cover - needs a broker
    """Subscribe at the end of ``topic`` with a throwaway group and return a started tail.

    Why wait for assignment: ``auto.offset.reset=latest`` only pins the position once partitions
    are assigned; records produced before that would be silently missed.
    """
    from confluent_kafka import Consumer

    consumer = Consumer(
        {
            "bootstrap.servers": bootstrap,
            "group.id": f"e2e-{topic}-{time.time_ns()}",
            "auto.offset.reset": "latest",
            "enable.auto.commit": False,
        }
    )
    consumer.subscribe([topic])
    deadline = time.monotonic() + 30
    while not consumer.assignment() and time.monotonic() < deadline:
        consumer.poll(0.5)
    tail = KafkaTail(consumer, topic)
    tail.start()
    return tail
