"""Spark DataFrame transformations: Kafka parsing, validation, windowed aggregation, unpivot."""

from __future__ import annotations

from pyspark.sql import Column, DataFrame
from pyspark.sql import functions as F  # noqa: N812
from pyspark.sql.types import DoubleType, StringType, StructField, StructType

from stream_processor.vitals import SPECS, Metric

EVENT_SCHEMA = StructType(
    [
        StructField("eventId", StringType()),
        StructField("patientId", StringType()),
        StructField("deviceId", StringType()),
        StructField("metric", StringType()),
        StructField("value", DoubleType()),
        StructField("unit", StringType()),
        StructField("loinc", StringType()),
        StructField("observedAt", StringType()),
        StructField("ingestedAt", StringType()),
        StructField("source", StringType()),
    ]
)

DEFAULT_MAX_FUTURE_SECONDS = 30  # equals the default watermark delay
MAX_AGE_HOURS = 24
_UUID_RE = r"^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
_PATIENT_RE = r"^P-\d{4,}$"


def _range_ok() -> Column:
    """Return True when `value` is inside its metric's valid range (False for unknown metrics)."""
    expr = F.lit(False)
    for metric, spec in SPECS.items():
        in_range = F.col("value").between(spec.valid_min, spec.valid_max)
        expr = F.when(F.col("metric") == metric.value, in_range).otherwise(expr)
    return expr


def parse_events(
    kafka_df: DataFrame,
    now: Column | None = None,
    max_future_seconds: int = DEFAULT_MAX_FUTURE_SECONDS,
) -> DataFrame:
    """Parse Kafka records into typed event columns plus a `valid` flag and the `correlationId`.

    Malformed rows are kept (valid = false) rather than dropped so the caller can count and log
    them before filtering with `valid_only`.

    Well-formed events whose `observedAt` is more than `max_future_seconds` ahead of processing
    time (`now`, default `current_timestamp()`, fixed per micro-batch) or older than 24 hours get
    `dropReason` = "future" / "stale". They must be removed before watermarking. The watermark is
    the max event time across all partitions minus its delay, so the allowance must not exceed that
    (see `assert_skew_within_watermark`); otherwise one future event silences every patient.
    """
    parsed = kafka_df.select(
        F.from_json(F.col("value").cast("string"), EVENT_SCHEMA).alias("e"),
        F.col("value").cast("string").alias("raw"),
        F.col("headers")
        if "headers" in kafka_df.columns
        else F.lit(None).cast("array<struct<key:string,value:binary>>").alias("headers"),
        F.col("topic"),
        F.col("partition"),
        F.col("offset"),
    )
    correlation = F.when(
        F.col("headers").isNotNull(),
        F.element_at(
            F.expr(
                "transform(filter(headers, h -> h.key = 'correlationId'), "
                "h -> cast(h.value as string))"
            ),
            1,
        ),
    )
    flat = parsed.select(
        F.col("e.eventId").alias("eventId"),
        F.col("e.patientId").alias("patientId"),
        F.col("e.deviceId").alias("deviceId"),
        F.col("e.metric").alias("metric"),
        F.col("e.value").alias("value"),
        F.col("e.unit").alias("unit"),
        F.col("e.observedAt").cast("timestamp").alias("observedAt"),
        correlation.alias("correlationId"),
        F.col("raw"),
        F.col("topic"),
        F.col("partition"),
        F.col("offset"),
    )
    valid = (
        F.col("eventId").rlike(_UUID_RE)
        & F.col("patientId").rlike(_PATIENT_RE)
        & F.col("deviceId").isNotNull()
        & F.col("unit").isNotNull()
        & F.col("observedAt").isNotNull()
        & F.col("value").isNotNull()
        & _range_ok()
    )
    processing_time = now if now is not None else F.current_timestamp()
    reason = F.when(
        F.col("observedAt") > processing_time + F.expr(f"INTERVAL {max_future_seconds} SECONDS"),
        "future",
    ).when(
        F.col("observedAt") < processing_time - F.expr(f"INTERVAL {MAX_AGE_HOURS} HOURS"), "stale"
    )
    return flat.withColumn("valid", F.coalesce(valid, F.lit(False))).withColumn(
        "dropReason", F.when(F.col("valid"), reason)
    )


def valid_only(df: DataFrame) -> DataFrame:
    """Keep well-formed events inside the event-time guard and drop the helper columns."""
    return df.filter(F.col("valid") & F.col("dropReason").isNull()).drop(
        "valid", "raw", "dropReason"
    )


def window_aggregate(events: DataFrame, window_duration: str, watermark: str | None) -> DataFrame:
    """Aggregate events into tumbling windows per patient, one wide row per patient window.

    For every metric M the row carries M_min, M_max, M_avg, M_cnt and M_last (value of the newest
    reading). Keeping all metrics in one row means NEWS2 and the ML feature vector see a complete
    patient window in a single update, whatever subset of metrics changed in the micro-batch.
    `lastCorrelationId` is the correlation id of the newest reading, propagated onto alerts.
    """
    source = events.withWatermark("observedAt", watermark) if watermark else events
    aggs: list[Column] = []
    for metric in Metric:
        m = metric.value
        is_m = F.col("metric") == m
        value_if = F.when(is_m, F.col("value"))
        aggs += [
            F.min(value_if).alias(f"{m}_min"),
            F.max(value_if).alias(f"{m}_max"),
            F.avg(value_if).alias(f"{m}_avg"),
            F.count(value_if).cast("int").alias(f"{m}_cnt"),
            F.max(F.when(is_m, F.struct("observedAt", "value"))).alias(f"{m}_lastS"),
        ]
    aggs.append(F.max(F.struct("observedAt", "correlationId")).alias("lastCorr"))
    grouped = source.groupBy(F.window("observedAt", window_duration).alias("w"), "patientId").agg(
        *aggs
    )
    cols: list[Column] = [
        F.col("patientId"),
        F.col("w.start").alias("windowStart"),
        F.col("w.end").alias("windowEnd"),
        F.col("lastCorr.correlationId").alias("lastCorrelationId"),
    ]
    for metric in Metric:
        m = metric.value
        cols += [
            F.col(f"{m}_min"),
            F.col(f"{m}_max"),
            F.col(f"{m}_avg"),
            F.col(f"{m}_cnt"),
            F.col(f"{m}_lastS.value").alias(f"{m}_last"),
        ]
    return grouped.select(*cols)


def unpivot_minutes(windows: DataFrame) -> DataFrame:
    """Turn wide patient windows into `vitals_by_minute` rows (one per patient, metric, window)."""
    parts = ", ".join(
        f"'{m.value}', {m.value}_min, {m.value}_max, {m.value}_avg, {m.value}_cnt" for m in Metric
    )
    stacked = windows.selectExpr(
        "patientId AS patient_id",
        "windowStart AS window_start",
        f"stack({len(Metric)}, {parts}) AS (metric, min, max, avg, count)",
    )
    return stacked.filter(F.col("count") > 0).select(
        "patient_id",
        "metric",
        F.to_date("window_start").alias("day"),
        "window_start",
        "min",
        "max",
        "avg",
        "count",
    )


def raw_rows(events: DataFrame) -> DataFrame:
    """Shape valid events as `vitals_raw` rows (CONTRACTS 5)."""
    return events.select(
        F.col("patientId").alias("patient_id"),
        F.to_date("observedAt").alias("day"),
        F.col("observedAt").alias("observed_at"),
        "metric",
        F.col("eventId").alias("event_id"),
        "value",
        "unit",
        F.col("deviceId").alias("device_id"),
    )


def archive_rows(events: DataFrame) -> DataFrame:
    """Shape valid events for the Parquet archive, adding the dt/hour partition columns."""
    return events.select(
        "eventId",
        "patientId",
        "deviceId",
        "metric",
        "value",
        "unit",
        "observedAt",
        F.date_format("observedAt", "yyyy-MM-dd").alias("dt"),
        F.date_format("observedAt", "HH").alias("hour"),
    )
