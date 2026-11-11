import json
from datetime import datetime, timezone

import pytest
from chispa import assert_df_equality
from pyspark.sql import DataFrame, SparkSession
from pyspark.sql import functions as F  # noqa: N812

from stream_processor.transforms import (
    archive_rows,
    parse_events,
    raw_rows,
    unpivot_minutes,
    valid_only,
    window_aggregate,
)

pytestmark = pytest.mark.spark

NOW = datetime(2026, 11, 11, 21, 0, 0)  # fixed "processing time" for deterministic tests


def parse(df: DataFrame) -> DataFrame:
    return parse_events(df, now=F.lit(NOW).cast("timestamp"))


KAFKA_SCHEMA = (
    "key binary, value binary, topic string, partition int, offset long, "
    "headers array<struct<key:string,value:binary>>"
)


def event(**overrides: object) -> dict[str, object]:
    base: dict[str, object] = {
        "eventId": "6f1c2a1e-8d0b-4c11-9a77-3f0c2b9e1d10",
        "patientId": "P-0001",
        "deviceId": "DEV-ICU-0001",
        "metric": "HEART_RATE",
        "value": 72.0,
        "unit": "/min",
        "loinc": "8867-4",
        "observedAt": "2026-11-11T20:15:30.123Z",
        "ingestedAt": "2026-11-11T20:15:30.456Z",
        "source": "DEVICE_BATCH",
    }
    base.update(overrides)
    return base


def kafka_df(spark: SparkSession, payloads: list[str | dict[str, object]], corr: str | None = None):
    rows = []
    for i, p in enumerate(payloads):
        text = p if isinstance(p, str) else json.dumps(p)
        headers = [("correlationId", corr.encode())] if corr else None
        rows.append((b"k", text.encode(), "vitals.raw", 0, i, headers))
    return spark.createDataFrame(rows, KAFKA_SCHEMA)


def test_parse_valid_event_types_and_correlation_id(spark: SparkSession) -> None:
    df = parse(kafka_df(spark, [event()], corr="corr-9"))
    row = df.collect()[0]
    assert row.valid is True
    assert row.patientId == "P-0001"
    assert row.value == 72.0
    assert row.observedAt == datetime(2026, 11, 11, 20, 15, 30, 123000)
    assert row.correlationId == "corr-9"


def test_missing_headers_yield_null_correlation_id(spark: SparkSession) -> None:
    row = parse(kafka_df(spark, [event()])).collect()[0]
    assert row.correlationId is None and row.valid is True


@pytest.mark.parametrize(
    "bad",
    [
        "not json at all",
        "{}",
        event(value="abc"),
        event(value=500.0),
        event(metric="PULSE"),
        event(patientId="X-1"),
        event(eventId="nope"),
        event(observedAt="yesterday"),
        event(unit=None),
        event(metric="SPO2", value=40.0),
    ],
)
def test_malformed_events_are_flagged_invalid(spark: SparkSession, bad: str | dict) -> None:
    df = parse(kafka_df(spark, [bad]))
    assert df.collect()[0].valid is False
    assert valid_only(df).count() == 0


def test_valid_only_drops_helper_columns(spark: SparkSession) -> None:
    df = valid_only(parse(kafka_df(spark, [event(), "garbage"])))
    assert df.count() == 1
    assert "valid" not in df.columns and "raw" not in df.columns


def _events(spark: SparkSession, specs: list[tuple[str, str, float, str, str | None]]) -> DataFrame:
    payloads = [
        event(
            eventId=f"00000000-0000-4000-8000-{i:012d}",
            patientId=pid,
            metric=metric,
            value=value,
            observedAt=ts,
        )
        for i, (pid, metric, value, ts, _) in enumerate(specs)
    ]
    return valid_only(parse(kafka_df(spark, payloads, corr="c1")))


def test_window_aggregate_min_max_avg_count_last(spark: SparkSession) -> None:
    ev = _events(
        spark,
        [
            ("P-0001", "HEART_RATE", 70.0, "2026-11-11T20:15:05Z", None),
            ("P-0001", "HEART_RATE", 90.0, "2026-11-11T20:15:50Z", None),
            ("P-0001", "HEART_RATE", 80.0, "2026-11-11T20:15:20Z", None),
            ("P-0001", "SPO2", 97.0, "2026-11-11T20:15:10Z", None),
            ("P-0001", "HEART_RATE", 100.0, "2026-11-11T20:16:01Z", None),
        ],
    )
    wide = window_aggregate(ev, "1 minute", watermark=None).orderBy("windowStart")
    first, second = wide.collect()
    assert first.windowStart == datetime(2026, 11, 11, 20, 15)
    assert first.windowEnd == datetime(2026, 11, 11, 20, 16)
    assert (first.HEART_RATE_min, first.HEART_RATE_max) == (70.0, 90.0)
    assert first.HEART_RATE_avg == pytest.approx(80.0)
    assert first.HEART_RATE_cnt == 3
    assert first.HEART_RATE_last == 90.0  # newest by observedAt, not arrival order
    assert first.SPO2_cnt == 1 and first.SPO2_last == 97.0
    assert first.RESP_RATE_cnt == 0 and first.RESP_RATE_avg is None
    assert first.lastCorrelationId == "c1"
    assert second.HEART_RATE_cnt == 1


def test_window_aggregate_separates_patients(spark: SparkSession) -> None:
    ev = _events(
        spark,
        [
            ("P-0001", "HEART_RATE", 70.0, "2026-11-11T20:15:05Z", None),
            ("P-0002", "HEART_RATE", 110.0, "2026-11-11T20:15:05Z", None),
        ],
    )
    wide = window_aggregate(ev, "1 minute", watermark=None)
    avgs = {r.patientId: r.HEART_RATE_avg for r in wide.collect()}
    assert avgs == {"P-0001": 70.0, "P-0002": 110.0}


def test_unpivot_minutes_matches_contract_shape(spark: SparkSession) -> None:
    ev = _events(
        spark,
        [
            ("P-0001", "HEART_RATE", 70.0, "2026-11-11T20:15:05Z", None),
            ("P-0001", "HEART_RATE", 90.0, "2026-11-11T20:15:50Z", None),
            ("P-0001", "SPO2", 97.0, "2026-11-11T20:15:10Z", None),
        ],
    )
    out = unpivot_minutes(window_aggregate(ev, "1 minute", watermark=None))
    expected = spark.createDataFrame(
        [
            (
                "P-0001",
                "HEART_RATE",
                "2026-11-11",
                datetime(2026, 11, 11, 20, 15),
                70.0,
                90.0,
                80.0,
                2,
            ),
            ("P-0001", "SPO2", "2026-11-11", datetime(2026, 11, 11, 20, 15), 97.0, 97.0, 97.0, 1),
        ],
        "patient_id string, metric string, day string, window_start timestamp, "
        "min double, max double, avg double, count int",
    ).withColumn("day", F.to_date("day"))
    assert_df_equality(out, expected, ignore_row_order=True, ignore_nullable=True)


def test_raw_rows_columns(spark: SparkSession) -> None:
    ev = _events(spark, [("P-0001", "HEART_RATE", 70.0, "2026-11-11T20:15:05Z", None)])
    out = raw_rows(ev)
    assert out.columns == [
        "patient_id", "day", "observed_at", "metric", "event_id", "value", "unit", "device_id",
    ]  # fmt: skip
    row = out.collect()[0]
    assert str(row.day) == "2026-11-11" and row.device_id == "DEV-ICU-0001"


def test_archive_rows_have_dt_and_hour_partitions(spark: SparkSession) -> None:
    ev = _events(spark, [("P-0001", "HEART_RATE", 70.0, "2026-11-11T20:15:05Z", None)])
    row = archive_rows(ev).collect()[0]
    assert (row.dt, row.hour) == ("2026-11-11", "20")
    assert datetime(2026, 11, 11, tzinfo=timezone.utc)  # keeps tz import meaningful


@pytest.mark.parametrize(
    ("observed_at", "reason"),
    [
        ("2026-11-11T21:04:59Z", None),
        ("2026-11-11T21:05:01Z", "future"),
        ("2099-01-01T00:00:00Z", "future"),
        ("2026-11-10T21:00:01Z", None),
        ("2026-11-10T20:59:59Z", "stale"),
        ("2001-01-01T00:00:00Z", "stale"),
    ],
)
def test_event_time_guard(spark: SparkSession, observed_at: str, reason: str | None) -> None:
    df = parse(kafka_df(spark, [event(observedAt=observed_at)]))
    row = df.collect()[0]
    assert row.dropReason == reason
    assert valid_only(df).count() == (0 if reason else 1)


def test_malformed_events_have_no_drop_reason(spark: SparkSession) -> None:
    assert parse(kafka_df(spark, ["garbage"])).collect()[0].dropReason is None
