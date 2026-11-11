import pytest

from stream_processor.config import Settings


def test_defaults_work_with_local_infra() -> None:
    s = Settings.from_env({})
    assert s.kafka_bootstrap_servers == "localhost:9092"
    assert s.redis_url == "redis://localhost:6379"
    assert s.cassandra_host == "localhost"
    assert s.cassandra_port == 9042
    assert s.cassandra_keyspace == "vitalstream"
    assert s.aws_endpoint_url == "http://localhost:4566"
    assert s.aws_region == "us-east-1"
    assert s.vitals_topic == "vitals.raw"
    assert s.alerts_topic == "alerts.detected"
    assert s.archive_uri == "s3a://vitalstream-archive/vitals_raw"
    assert s.trigger_mode == "processingTime"
    assert s.watermark == "30 seconds"
    assert s.window_duration == "1 minute"
    assert s.metrics_port == 9404
    assert s.log_level == "INFO"


def test_environment_overrides() -> None:
    s = Settings.from_env(
        {
            "KAFKA_BOOTSTRAP_SERVERS": "kafka:29092",
            "CASSANDRA_CONTACT_POINTS": "cassandra:9043",
            "REDIS_URL": "redis://redis:6379",
            "CHECKPOINT_DIR": "/data/ckpt",
            "TRIGGER_MODE": "availableNow",
            "ARCHIVE_ENABLED": "false",
            "MAX_OFFSETS_PER_TRIGGER": "5000",
            "LOG_LEVEL": "debug",
        }
    )
    assert s.kafka_bootstrap_servers == "kafka:29092"
    assert (s.cassandra_host, s.cassandra_port) == ("cassandra", 9043)
    assert s.redis_url == "redis://redis:6379"
    assert s.checkpoint_dir == "/data/ckpt"
    assert s.trigger_mode == "availableNow"
    assert s.archive_enabled is False
    assert s.max_offsets_per_trigger == 5000
    assert s.log_level == "DEBUG"


def test_cassandra_contact_point_without_port_uses_default() -> None:
    s = Settings.from_env({"CASSANDRA_CONTACT_POINTS": "cass-1"})
    assert (s.cassandra_host, s.cassandra_port) == ("cass-1", 9042)


def test_first_contact_point_wins_for_comma_separated_lists() -> None:
    s = Settings.from_env({"CASSANDRA_CONTACT_POINTS": "a:9042,b:9042"})
    assert s.cassandra_host == "a"


def test_invalid_trigger_mode_rejected() -> None:
    with pytest.raises(ValueError, match="TRIGGER_MODE"):
        Settings.from_env({"TRIGGER_MODE": "continuous"})


def test_invalid_boolean_rejected() -> None:
    with pytest.raises(ValueError, match="ARCHIVE_ENABLED"):
        Settings.from_env({"ARCHIVE_ENABLED": "maybe"})


def test_spark_packages_default_and_empty_override() -> None:
    assert "spark-sql-kafka-0-10_2.12:3.5.8" in Settings.from_env({}).spark_packages
    assert Settings.from_env({"SPARK_PACKAGES": ""}).spark_packages == ""


def test_watermark_seconds_parsed_from_interval_string() -> None:
    assert Settings.from_env({}).watermark_seconds == 30
    assert Settings.from_env({"WATERMARK": "2 minutes"}).watermark_seconds == 120
    assert Settings.from_env({"WATERMARK": "45 seconds"}).watermark_seconds == 45


def test_max_future_skew_is_derived_from_the_watermark() -> None:
    assert Settings.from_env({}).max_future_skew_seconds == 30
    assert Settings.from_env({"WATERMARK": "10 seconds"}).max_future_skew_seconds == 10


def test_bad_watermark_rejected() -> None:
    with pytest.raises(ValueError, match="WATERMARK"):
        Settings.from_env({"WATERMARK": "soon"})


def test_startup_assertion_rejects_skew_above_watermark() -> None:
    from stream_processor.config import assert_skew_within_watermark

    assert_skew_within_watermark(30, 30)
    assert_skew_within_watermark(10, 30)
    with pytest.raises(ValueError, match="MAX_FUTURE_SKEW"):
        assert_skew_within_watermark(31, 30)
