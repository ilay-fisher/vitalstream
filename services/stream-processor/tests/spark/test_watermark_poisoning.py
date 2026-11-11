"""A far-future event must not poison the watermark and silence later windows."""

import json
from pathlib import Path

import pytest
from pyspark.sql import DataFrame, SparkSession
from pyspark.sql import functions as F  # noqa: N812

from stream_processor.transforms import parse_events, valid_only, window_aggregate

pytestmark = pytest.mark.spark


def _event(i: int, observed_at: str, patient: str = "P-0001") -> str:
    return json.dumps(
        {
            "eventId": f"00000000-0000-4000-8000-{i:012d}",
            "patientId": patient,
            "deviceId": "DEV-ICU-0001",
            "metric": "HEART_RATE",
            "value": 80.0,
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


def _run(spark: SparkSession, tmp_path: Path, guard: bool) -> set[str]:
    source = tmp_path / f"in-{guard}"
    source.mkdir()
    seen: list[str] = []

    def collect(batch: DataFrame, _batch_id: int) -> None:
        seen.extend(str(r.windowStart) for r in batch.collect())

    def drain() -> None:
        windows = window_aggregate(_stream(spark, source, guard), "1 minute", "30 seconds")
        query = (
            windows.writeStream.outputMode("update")
            .foreachBatch(collect)
            .option("checkpointLocation", str(tmp_path / f"ckpt-{guard}"))
            .trigger(availableNow=True)
            .start()
        )
        query.awaitTermination()

    (source / "1.txt").write_text(
        "\n".join([_event(1, "2099-01-01T00:00:00Z"), _event(2, "2026-11-11T20:00:10Z")]) + "\n"
    )
    drain()
    (source / "2.txt").write_text(
        "\n".join(_event(10 + i, f"2026-11-11T20:0{i}:10Z") for i in range(1, 5)) + "\n"
    )
    drain()
    return set(seen)


def test_guard_keeps_later_windows_flowing(spark: SparkSession, tmp_path: Path) -> None:
    windows = _run(spark, tmp_path, guard=True)
    assert {"2026-11-11 20:01:00", "2026-11-11 20:02:00", "2026-11-11 20:04:00"} <= windows
    assert not any(w.startswith("2099") for w in windows)


def test_without_guard_the_watermark_is_poisoned(spark: SparkSession, tmp_path: Path) -> None:
    windows = _run(spark, tmp_path, guard=False)
    assert "2026-11-11 20:03:00" not in windows  # control: proves the test detects poisoning
