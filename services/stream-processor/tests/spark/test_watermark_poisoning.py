"""A far-future event must not poison the watermark and silence later windows."""

import json
from datetime import datetime, timezone
from pathlib import Path

import pytest
from pyspark.sql import DataFrame, Row, SparkSession
from pyspark.sql import functions as F  # noqa: N812

from stream_processor.detection import detect_window_alerts
from stream_processor.pipeline import _snapshot
from stream_processor.transforms import parse_events, valid_only, window_aggregate

pytestmark = pytest.mark.spark


def _event(i: int, observed_at: str, patient: str = "P-0001", value: float = 80.0) -> str:
    return json.dumps(
        {
            "eventId": f"00000000-0000-4000-8000-{i:012d}",
            "patientId": patient,
            "deviceId": "DEV-ICU-0001",
            "metric": "HEART_RATE",
            "value": value,
            "unit": "/min",
            "loinc": "8867-4",
            "observedAt": observed_at,
            "ingestedAt": observed_at,
            "source": "DEVICE_BATCH",
        }
    )


def _stream(spark: SparkSession, source: Path, guard: bool) -> DataFrame:
    lines = (
        spark.readStream.format("text")
        .load(str(source))
        .select(
            F.col("value").cast("binary").alias("value"),
            F.lit("vitals.raw").alias("topic"),
            F.lit(0).alias("partition"),
            F.lit(0).cast("long").alias("offset"),
        )
    )
    # Pin "processing time" a little after the data so the test is date-independent.
    parsed = parse_events(lines, now=F.lit("2026-11-11 21:00:00").cast("timestamp"))
    if guard:
        return valid_only(parsed)
    return parsed.filter("valid").drop("valid", "raw", "dropReason")


def _run(spark: SparkSession, tmp_path: Path, guard: bool, poison_at: str) -> list[Row]:
    source = tmp_path / f"in-{guard}-{poison_at}"
    source.mkdir()
    seen: list[Row] = []

    def collect(batch: DataFrame, _batch_id: int) -> None:
        seen.extend(batch.collect())

    def drain() -> None:
        windows = window_aggregate(_stream(spark, source, guard), "1 minute", "30 seconds")
        query = (
            windows.writeStream.outputMode("update")
            .foreachBatch(collect)
            .option("checkpointLocation", str(tmp_path / f"ckpt-{guard}-{poison_at}"))
            .trigger(availableNow=True)
            .start()
        )
        query.awaitTermination()

    (source / "1.txt").write_text(_event(1, poison_at) + "\n")
    drain()
    # An on-time, clinically alarming reading of ANOTHER patient arrives afterwards.
    (source / "2.txt").write_text(_event(2, "2026-11-11T20:59:30Z", "P-0002", value=140.0) + "\n")
    drain()
    return seen


@pytest.mark.parametrize(
    "poison_at", ["2026-11-11T21:00:31Z", "2026-11-11T21:05:00Z", "2026-11-11T22:00:00Z"]
)
def test_guard_keeps_other_patients_flowing_and_alerting(
    spark: SparkSession, tmp_path: Path, poison_at: str
) -> None:
    seen = _run(spark, tmp_path, True, poison_at)
    rows = [r for r in seen if r.patientId == "P-0002"]
    assert len(rows) == 1
    alerts = detect_window_alerts(_snapshot(rows[0]), None, datetime.now(timezone.utc))
    assert any(a.type.value == "THRESHOLD" and a.severity.value == "CRITICAL" for a in alerts)
    assert not [r for r in seen if r.patientId == "P-0001"]  # the poison event itself is dropped


def test_without_guard_the_watermark_is_poisoned(spark: SparkSession, tmp_path: Path) -> None:
    rows = _run(spark, tmp_path, False, "2026-11-11T21:05:00Z")
    assert not [r for r in rows if r.patientId == "P-0002"]  # control: suppression happens
