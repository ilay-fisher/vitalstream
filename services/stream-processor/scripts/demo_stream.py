"""Produce a demo `vitals.raw` stream: one stable patient and one deteriorating patient.

Usage: python scripts/demo_stream.py --bootstrap localhost:22092 [--minutes 18]

Event time is anchored to "now" (inside the 24 h guard); the seed makes values reproducible. Every reading carries a
`correlationId` header, like the ingest-gateway does.
"""

from __future__ import annotations

import argparse
import json
import random
import uuid
from collections.abc import Iterator
from datetime import datetime, timedelta, timezone
from typing import Any

STEP_SECONDS = 5

UNITS = {
    "HEART_RATE": ("/min", "8867-4"),
    "SPO2": ("%", "59408-5"),
    "RESP_RATE": ("/min", "9279-1"),
    "SYSTOLIC_BP": ("mm[Hg]", "8480-6"),
    "DIASTOLIC_BP": ("mm[Hg]", "8462-4"),
    "TEMPERATURE": ("Cel", "8310-5"),
}
# metric -> (baseline, noise, value at the end of the deterioration ramp)
STABLE = {
    "HEART_RATE": (74.0, 1.5, 74.0),
    "SPO2": (97.5, 0.4, 97.5),
    "RESP_RATE": (15.0, 0.8, 15.0),
    "SYSTOLIC_BP": (122.0, 2.5, 122.0),
    "DIASTOLIC_BP": (76.0, 1.5, 76.0),
    "TEMPERATURE": (36.8, 0.08, 36.8),
}
DETERIORATING = {
    "HEART_RATE": (74.0, 1.5, 138.0),
    "SPO2": (97.5, 0.4, 87.0),
    "RESP_RATE": (15.0, 0.8, 29.0),
    "SYSTOLIC_BP": (122.0, 2.5, 88.0),
    "DIASTOLIC_BP": (76.0, 1.5, 60.0),
    "TEMPERATURE": (36.8, 0.08, 38.9),
}


def default_base(minutes: int) -> datetime:
    """Start far enough back that the whole stream ends ~1 minute ago (inside the time guard)."""
    now = datetime.now(timezone.utc).replace(second=0, microsecond=0)
    return now - timedelta(minutes=minutes + 1)


def poison_event(base: datetime) -> dict[str, Any]:
    """Build a far-future reading that would poison the watermark if it were not dropped."""
    return {
        "eventId": str(uuid.uuid5(uuid.NAMESPACE_URL, "poison")),
        "patientId": "P-0001",
        "deviceId": "DEV-ICU-0001",
        "metric": "HEART_RATE",
        "value": 72.0,
        "unit": "/min",
        "loinc": "8867-4",
        "observedAt": (base + timedelta(days=3650)).strftime("%Y-%m-%dT%H:%M:%S.000Z"),
        "ingestedAt": base.strftime("%Y-%m-%dT%H:%M:%S.100Z"),
        "source": "DEVICE_BATCH",
    }


def generate_events(
    minutes: int = 18,
    baseline_minutes: int = 10,
    seed: int = 7,
    base: datetime | None = None,
    poison: bool = False,
) -> Iterator[dict[str, Any]]:
    """Yield events for P-0001 (stable) and P-0002 (deteriorates after `baseline_minutes`)."""
    rng = random.Random(seed)
    start = base or default_base(minutes)
    if poison:
        yield poison_event(start)
    steps = minutes * 60 // STEP_SECONDS
    ramp_start = baseline_minutes * 60 // STEP_SECONDS
    for step in range(steps):
        observed = start + timedelta(seconds=step * STEP_SECONDS)
        for patient, profile, deteriorates in (
            ("P-0001", STABLE, False),
            ("P-0002", DETERIORATING, True),
        ):
            progress = 0.0
            if deteriorates and step >= ramp_start:
                progress = (step - ramp_start + 1) / (steps - ramp_start)
            for metric, (base, noise, end) in profile.items():
                value = base + (end - base) * progress + rng.gauss(0, noise)
                unit, loinc = UNITS[metric]
                yield {
                    "eventId": str(uuid.uuid5(uuid.NAMESPACE_URL, f"{patient}|{metric}|{step}")),
                    "patientId": patient,
                    "deviceId": "DEV-ICU-0001" if patient == "P-0001" else "DEV-ICU-0002",
                    "metric": metric,
                    "value": round(value, 2),
                    "unit": unit,
                    "loinc": loinc,
                    "observedAt": observed.strftime("%Y-%m-%dT%H:%M:%S.000Z"),
                    "ingestedAt": observed.strftime("%Y-%m-%dT%H:%M:%S.100Z"),
                    "source": "DEVICE_BATCH",
                }


def produce(bootstrap: str, topic: str, events: Iterator[dict[str, Any]]) -> int:
    """Publish events (key = patientId, headers like the gateway) and return the count."""
    from confluent_kafka import Producer

    producer = Producer({"bootstrap.servers": bootstrap, "enable.idempotence": True})
    count = 0
    for event in events:
        producer.produce(
            topic,
            key=event["patientId"],
            value=json.dumps(event),
            headers=[
                ("correlationId", f"demo-{abs(hash(event['eventId'])) % 1000:03d}".encode()),
                ("schemaVersion", b"1"),
                ("source", b"demo-stream"),
            ],
        )
        count += 1
        if count % 500 == 0:
            producer.poll(0)
    producer.flush(60)
    return count


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--bootstrap", default="localhost:9092")
    parser.add_argument("--topic", default="vitals.raw")
    parser.add_argument("--minutes", type=int, default=18)
    parser.add_argument("--poison", action="store_true", help="prepend a far-future event")
    args = parser.parse_args()
    sent = produce(args.bootstrap, args.topic, generate_events(args.minutes, poison=args.poison))
    print(f"produced {sent} events to {args.topic}")


if __name__ == "__main__":
    main()
