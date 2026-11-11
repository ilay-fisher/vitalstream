"""12-factor configuration: every knob is an environment variable with a local-infra default."""

from __future__ import annotations

from collections.abc import Mapping
from dataclasses import dataclass

DEFAULT_SPARK_PACKAGES = ",".join(
    [
        "org.apache.spark:spark-sql-kafka-0-10_2.12:3.5.8",
        "com.datastax.spark:spark-cassandra-connector_2.12:3.5.1",
        "org.apache.hadoop:hadoop-aws:3.3.4",
        "com.amazonaws:aws-java-sdk-bundle:1.12.262",
    ]
)

DEV_PSEUDONYM_KEY = "dev-only-pseudonym-key"

_TRUE = {"1", "true", "yes", "on"}
_FALSE = {"0", "false", "no", "off"}


def _bool(env: Mapping[str, str], name: str, default: bool) -> bool:
    raw = env.get(name)
    if raw is None:
        return default
    lowered = raw.strip().lower()
    if lowered in _TRUE:
        return True
    if lowered in _FALSE:
        return False
    raise ValueError(f"{name} must be a boolean, got {raw!r}")


_UNIT_SECONDS = {"second": 1, "minute": 60, "hour": 3600}


def parse_interval_seconds(text: str, name: str = "interval") -> int:
    """Parse "30 seconds" / "1 minute" / "2 hours" into whole seconds."""
    parts = text.strip().lower().split()
    if len(parts) == 2 and parts[0].isdigit() and parts[1].rstrip("s") in _UNIT_SECONDS:
        return int(parts[0]) * _UNIT_SECONDS[parts[1].rstrip("s")]
    raise ValueError(f"{name} must look like '30 seconds', got {text!r}")


def assert_skew_within_watermark(max_future_skew_seconds: int, watermark_seconds: int) -> None:
    """Fail fast if the future-event allowance could move the watermark past real time.

    Spark's watermark is the maximum event time seen across all partitions minus the delay, so an
    event `d` seconds ahead of processing time moves it to now + d - watermark. Only d <= watermark
    keeps it from overtaking real time and turning every on-time event into a late one.
    """
    if max_future_skew_seconds > watermark_seconds:
        raise ValueError(
            f"MAX_FUTURE_SKEW ({max_future_skew_seconds}s) must not exceed the watermark delay "
            f"({watermark_seconds}s)"
        )


def _split_host_port(contact_points: str, default_port: int = 9042) -> tuple[str, int]:
    first = contact_points.split(",")[0].strip()
    host, _, port = first.partition(":")
    return host, int(port) if port else default_port


@dataclass(frozen=True)
class Settings:
    """Runtime configuration, see README for the table."""

    kafka_bootstrap_servers: str
    redis_url: str
    cassandra_host: str
    cassandra_port: int
    cassandra_keyspace: str
    aws_endpoint_url: str
    aws_region: str
    aws_access_key_id: str
    aws_secret_access_key: str
    vitals_topic: str
    alerts_topic: str
    archive_enabled: bool
    archive_uri: str
    checkpoint_dir: str
    model_dir: str
    trigger_mode: str
    trigger_interval: str
    starting_offsets: str
    max_offsets_per_trigger: int
    window_duration: str
    watermark: str
    spark_master: str
    spark_packages: str
    metrics_port: int
    log_level: str
    log_pseudonym_key: str
    app_env: str
    service_name: str = "stream-processor"

    @property
    def watermark_seconds(self) -> int:
        """Watermark delay in seconds."""
        return parse_interval_seconds(self.watermark, "WATERMARK")

    @property
    def max_future_skew_seconds(self) -> int:
        """Largest accepted `observedAt` lead over processing time; derived, never configured."""
        return self.watermark_seconds

    @classmethod
    def from_env(cls, env: Mapping[str, str]) -> Settings:
        """Build settings from an environment mapping (pass `os.environ`)."""
        trigger_mode = env.get("TRIGGER_MODE", "processingTime")
        if trigger_mode not in {"processingTime", "availableNow"}:
            raise ValueError(
                f"TRIGGER_MODE must be processingTime or availableNow, got {trigger_mode!r}"
            )
        parse_interval_seconds(env.get("WATERMARK", "30 seconds"), "WATERMARK")
        host, port = _split_host_port(env.get("CASSANDRA_CONTACT_POINTS", "localhost:9042"))
        return cls(
            kafka_bootstrap_servers=env.get("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092"),
            redis_url=env.get("REDIS_URL", "redis://localhost:6379"),
            cassandra_host=host,
            cassandra_port=port,
            cassandra_keyspace=env.get("CASSANDRA_KEYSPACE", "vitalstream"),
            aws_endpoint_url=env.get("AWS_ENDPOINT_URL", "http://localhost:4566"),
            aws_region=env.get("AWS_REGION", "us-east-1"),
            aws_access_key_id=env.get("AWS_ACCESS_KEY_ID", "test"),
            aws_secret_access_key=env.get("AWS_SECRET_ACCESS_KEY", "test"),
            vitals_topic=env.get("VITALS_TOPIC", "vitals.raw"),
            alerts_topic=env.get("ALERTS_TOPIC", "alerts.detected"),
            archive_enabled=_bool(env, "ARCHIVE_ENABLED", True),
            archive_uri=env.get("ARCHIVE_URI", "s3a://vitalstream-archive/vitals_raw"),
            checkpoint_dir=env.get("CHECKPOINT_DIR", "/tmp/vitalstream/checkpoints"),
            model_dir=env.get("ANOMALY_MODEL_DIR", "/tmp/vitalstream/models/anomaly"),
            trigger_mode=trigger_mode,
            trigger_interval=env.get("TRIGGER_INTERVAL", "5 seconds"),
            starting_offsets=env.get("STARTING_OFFSETS", "earliest"),
            max_offsets_per_trigger=int(env.get("MAX_OFFSETS_PER_TRIGGER", "50000")),
            window_duration=env.get("WINDOW_DURATION", "1 minute"),
            watermark=env.get("WATERMARK", "30 seconds"),
            spark_master=env.get("SPARK_MASTER", "local[*]"),
            spark_packages=env.get("SPARK_PACKAGES", DEFAULT_SPARK_PACKAGES),
            metrics_port=int(env.get("METRICS_PORT", "9404")),
            log_level=env.get("LOG_LEVEL", "INFO").upper(),
            log_pseudonym_key=env.get("LOG_PSEUDONYM_KEY", DEV_PSEUDONYM_KEY),
            app_env=env.get("APP_ENV", "dev").strip().lower(),
        )
