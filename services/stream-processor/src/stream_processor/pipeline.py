"""Streaming queries and their foreachBatch handlers.

Two queries read `vitals.raw`:

* ``raw-sink``: every valid event -> Cassandra `vitals_raw`, Parquet archive, Redis latest hash,
  EWMA state + TREND_ZSCORE alerts.
* ``window-detect``: 1-minute tumbling windows (watermark 30 s, update mode) -> Cassandra
  `vitals_by_minute`, THRESHOLD / NEWS2 / ML_ANOMALY alerts.

Every sink is idempotent under replay of the same micro-batch (see README).
"""

from __future__ import annotations

from collections import defaultdict
from dataclasses import dataclass, field
from datetime import datetime, timezone
from typing import TYPE_CHECKING, Any

from pyspark.sql import DataFrame, Row
from pyspark.sql import functions as F  # noqa: N812
from pyspark.sql.types import ArrayType, BinaryType, StringType, StructField, StructType

from stream_processor.alerts import Alert
from stream_processor.anomaly import AnomalyModel
from stream_processor.config import Settings
from stream_processor.detection import WindowSnapshot, detect_trend_alerts, detect_window_alerts
from stream_processor.ewma import EwmaConfig, EwmaState
from stream_processor.logging_setup import bind_correlation_id, get_logger
from stream_processor.metrics import Metrics
from stream_processor.privacy import patient_ref
from stream_processor.redis_state import AlertLedger, EwmaStore, LatestReading, LatestStore
from stream_processor.transforms import (
    archive_rows,
    parse_events,
    raw_rows,
    unpivot_minutes,
    valid_only,
    window_aggregate,
)
from stream_processor.vitals import Metric

if TYPE_CHECKING:
    from pyspark.sql import SparkSession
    from pyspark.sql.streaming.query import StreamingQuery

CASSANDRA_FORMAT = "org.apache.spark.sql.cassandra"
SCHEMA_VERSION = "1"
MAX_LOGGED_DROPS = 20

_KAFKA_OUT_SCHEMA = StructType(
    [
        StructField("key", StringType()),
        StructField("value", StringType()),
        StructField(
            "headers",
            ArrayType(
                StructType([StructField("key", StringType()), StructField("value", BinaryType())])
            ),
        ),
    ]
)


def _utcnow() -> datetime:
    return datetime.now(timezone.utc)


def _iso_ms(ms: int) -> str:
    return datetime.fromtimestamp(ms / 1000, tz=timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.") + (
        f"{ms % 1000:03d}Z"
    )


@dataclass
class Deps:
    """Everything the batch handlers need; built once in `main`."""

    settings: Settings
    metrics: Metrics
    redis: Any
    model: AnomalyModel | None
    ewma_cfg: EwmaConfig = field(default_factory=EwmaConfig)


class AlertPublisher:
    """Publish alerts to `alerts.detected` once per (alertId, severity)."""

    def __init__(self, spark: SparkSession, deps: Deps) -> None:
        self._spark = spark
        self._deps = deps
        self._ledger = AlertLedger(deps.redis)

    def publish(self, alerts: list[tuple[Alert, str | None]]) -> int:
        """Send new/escalated alerts to Kafka, then record them; return the number sent."""
        by_id = {a.alert_id: (a, corr) for a, corr in alerts}
        fresh = self._ledger.filter_new([a for a, _ in alerts])
        if not fresh:
            return 0
        rows = []
        for alert in fresh:
            corr = by_id[alert.alert_id][1] or ""
            headers = [
                ("correlationId", corr.encode()),
                ("schemaVersion", SCHEMA_VERSION.encode()),
                ("source", self._deps.settings.service_name.encode()),
            ]
            rows.append((alert.patient_id, alert.to_json(), headers))
        (
            self._spark.createDataFrame(rows, _KAFKA_OUT_SCHEMA)
            .write.format("kafka")
            .option("kafka.bootstrap.servers", self._deps.settings.kafka_bootstrap_servers)
            .option("topic", self._deps.settings.alerts_topic)
            .save()
        )
        self._ledger.record(fresh)
        log = get_logger()
        for alert in fresh:
            self._deps.metrics.alerts_raised.labels(
                type=alert.type.value, severity=alert.severity.value
            ).inc()
            bind_correlation_id(by_id[alert.alert_id][1])
            log.info(
                "alert raised",
                alertId=alert.alert_id,
                patientRef=patient_ref(alert.patient_id, self._deps.settings.log_pseudonym_key),
                type=alert.type.value,
                severity=alert.severity.value,
                metric=alert.metric.value if alert.metric else None,
            )
        bind_correlation_id(None)
        return len(fresh)


def _cassandra_write(df: DataFrame, settings: Settings, table: str) -> None:
    (
        df.write.format(CASSANDRA_FORMAT)
        .options(table=table, keyspace=settings.cassandra_keyspace)
        .mode("append")
        .save()
    )


class RawBatchHandler:
    """foreachBatch handler for the raw-event query."""

    def __init__(self, spark: SparkSession, deps: Deps) -> None:
        self._deps = deps
        self._publisher = AlertPublisher(spark, deps)
        self._ewma = EwmaStore(deps.redis)
        self._latest = LatestStore(deps.redis)

    def __call__(self, batch: DataFrame, batch_id: int) -> None:
        """Process one micro-batch of parsed Kafka records."""
        log = get_logger()
        deps = self._deps
        batch.persist()
        try:
            invalid = batch.filter(~F.col("valid")).count()
            if invalid:
                deps.metrics.malformed_events.inc(invalid)
                sample = (
                    batch.filter(~F.col("valid")).select("topic", "partition", "offset").limit(3)
                )
                log.warning(
                    "malformed events dropped",
                    batchId=batch_id,
                    count=invalid,
                    samples=[r.asDict() for r in sample.collect()],
                )
            dropped = batch.filter(F.col("dropReason").isNotNull()).select("eventId", "dropReason")
            for row in dropped.limit(MAX_LOGGED_DROPS).collect():
                log.warning(
                    "event dropped by event-time guard", eventId=row.eventId, reason=row.dropReason
                )
            for row in dropped.groupBy("dropReason").count().collect():
                deps.metrics.events_dropped.labels(reason=row.dropReason).inc(row["count"])
            events = valid_only(batch)
            count = events.count()
            if count == 0:
                return
            _cassandra_write(raw_rows(events), deps.settings, "vitals_raw")
            if deps.settings.archive_enabled:
                (
                    archive_rows(events)
                    .write.mode("append")
                    .partitionBy("dt", "hour")
                    .parquet(deps.settings.archive_uri)
                )
            rows = events.select(
                "patientId",
                "metric",
                "value",
                "unit",
                "correlationId",
                (F.col("observedAt").cast("double") * 1000).cast("long").alias("ts"),
            ).collect()
            self._update_redis_and_detect(rows, batch_id)
            deps.metrics.events_processed.inc(count)
            deps.metrics.batches.labels(query="raw-sink").inc()
            log.info("raw batch written", batchId=batch_id, events=count)
        finally:
            batch.unpersist()

    def _update_redis_and_detect(self, rows: list[Row], batch_id: int) -> None:
        latest: dict[tuple[str, Metric], LatestReading] = {}
        series: dict[tuple[str, Metric], list[tuple[int, float]]] = defaultdict(list)
        corr: dict[tuple[str, Metric], str | None] = {}
        for r in rows:
            key = (r.patientId, Metric(r.metric))
            series[key].append((r.ts, r.value))
            current = latest.get(key)
            if current is None or r.ts >= current.observed_at_ms:
                latest[key] = LatestReading(
                    r.patientId, key[1], r.value, r.unit, r.ts, _iso_ms(r.ts)
                )
                corr[key] = r.correlationId
        self._latest.update(latest.values())

        states = self._ewma.load_many(series.keys())
        new_states: dict[tuple[str, Metric], EwmaState] = {}
        alerts: list[tuple[Alert, str | None]] = []
        now = _utcnow()
        for key, samples in series.items():
            state, trend_alerts = detect_trend_alerts(
                key[0], key[1], states.get(key), samples, self._deps.ewma_cfg, now
            )
            if state is not None and state != states.get(key):
                new_states[key] = state
            alerts.extend((a, corr.get(key)) for a in trend_alerts)
        # Publish before persisting EWMA state: a crash in between replays the batch, which
        # re-derives the same alertIds (deduplicated), whereas the reverse order could lose alerts.
        self._publisher.publish(alerts)
        self._ewma.save_many(new_states)


class WindowBatchHandler:
    """foreachBatch handler for the windowed-aggregate query."""

    def __init__(self, spark: SparkSession, deps: Deps) -> None:
        self._deps = deps
        self._publisher = AlertPublisher(spark, deps)

    def __call__(self, batch: DataFrame, batch_id: int) -> None:
        """Process the windows updated by one micro-batch."""
        deps = self._deps
        batch.persist()
        try:
            rows = batch.collect()
            if not rows:
                return
            _cassandra_write(unpivot_minutes(batch), deps.settings, "vitals_by_minute")
            now = _utcnow()
            alerts: list[tuple[Alert, str | None]] = []
            for row in rows:
                snapshot = _snapshot(row)
                alerts.extend(
                    (a, snapshot.correlation_id)
                    for a in detect_window_alerts(snapshot, deps.model, now)
                )
            sent = self._publisher.publish(alerts)
            deps.metrics.batches.labels(query="window-detect").inc()
            get_logger().info(
                "window batch written", batchId=batch_id, windows=len(rows), alertsSent=sent
            )
        finally:
            batch.unpersist()


def _snapshot(row: Row) -> WindowSnapshot:
    data = row.asDict()
    return WindowSnapshot(
        patient_id=data["patientId"],
        window_start=data["windowStart"].replace(tzinfo=timezone.utc),
        window_end=data["windowEnd"].replace(tzinfo=timezone.utc),
        avg={m: data[f"{m.value}_avg"] for m in Metric},
        last={m: data[f"{m.value}_last"] for m in Metric},
        correlation_id=data["lastCorrelationId"],
    )


def read_events(spark: SparkSession, settings: Settings) -> DataFrame:
    """Streaming DataFrame of parsed `vitals.raw` records (invalid rows flagged, not dropped)."""
    reader = (
        spark.readStream.format("kafka")
        .option("kafka.bootstrap.servers", settings.kafka_bootstrap_servers)
        .option("subscribe", settings.vitals_topic)
        .option("startingOffsets", settings.starting_offsets)
        .option("maxOffsetsPerTrigger", settings.max_offsets_per_trigger)
        .option("includeHeaders", "true")
        .option("failOnDataLoss", "false")
    )
    return parse_events(reader.load(), max_future_seconds=settings.max_future_skew_seconds)


def _trigger(settings: Settings) -> dict[str, Any]:
    if settings.trigger_mode == "availableNow":
        return {"availableNow": True}
    return {"processingTime": settings.trigger_interval}


def start_queries(spark: SparkSession, deps: Deps) -> list[StreamingQuery]:
    """Start both streaming queries and return them."""
    settings = deps.settings
    events = read_events(spark, settings)
    base = settings.checkpoint_dir.rstrip("/")

    raw_query = (
        events.writeStream.queryName("raw-sink")
        .foreachBatch(RawBatchHandler(spark, deps))
        .option("checkpointLocation", f"{base}/raw-sink")
        .trigger(**_trigger(settings))
        .start()
    )
    windows = window_aggregate(valid_only(events), settings.window_duration, settings.watermark)
    window_query = (
        windows.writeStream.queryName("window-detect")
        .outputMode("update")
        .foreachBatch(WindowBatchHandler(spark, deps))
        .option("checkpointLocation", f"{base}/window-detect")
        .trigger(**_trigger(settings))
        .start()
    )
    return [raw_query, window_query]
