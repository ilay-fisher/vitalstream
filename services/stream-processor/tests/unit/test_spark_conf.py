from stream_processor.config import Settings
from stream_processor.spark_session import spark_conf


def test_prometheus_servlet_and_utc_enabled() -> None:
    conf = spark_conf(Settings.from_env({}), "app")
    assert conf["spark.metrics.conf.*.sink.prometheusServlet.path"] == "/metrics/prometheus"
    assert conf["spark.ui.prometheus.enabled"] == "true"
    assert conf["spark.ui.port"] == "4040"
    assert conf["spark.sql.session.timeZone"] == "UTC"


def test_connectors_point_at_configured_infra() -> None:
    conf = spark_conf(
        Settings.from_env(
            {"CASSANDRA_CONTACT_POINTS": "cass:9043", "AWS_ENDPOINT_URL": "http://localstack:4566"}
        ),
        "app",
    )
    assert conf["spark.cassandra.connection.host"] == "cass"
    assert conf["spark.cassandra.connection.port"] == "9043"
    assert conf["spark.hadoop.fs.s3a.endpoint"] == "http://localstack:4566"
    assert conf["spark.hadoop.fs.s3a.path.style.access"] == "true"
    assert conf["spark.hadoop.fs.s3a.connection.ssl.enabled"] == "false"


def test_packages_omitted_when_jars_are_baked_in() -> None:
    assert "spark.jars.packages" not in spark_conf(Settings.from_env({"SPARK_PACKAGES": ""}), "a")
    assert "kafka" in spark_conf(Settings.from_env({}), "a")["spark.jars.packages"]
