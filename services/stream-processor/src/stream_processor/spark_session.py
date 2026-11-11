"""SparkSession construction: connectors, S3A to LocalStack, Prometheus servlet, UTC."""

from __future__ import annotations

from typing import TYPE_CHECKING

from stream_processor.config import Settings

if TYPE_CHECKING:
    from pyspark.sql import SparkSession


def spark_conf(settings: Settings, app_name: str) -> dict[str, str]:
    """Return the Spark configuration as a plain dict (kept separate so it is unit-testable)."""
    conf = {
        "spark.app.name": app_name,
        "spark.master": settings.spark_master,
        "spark.sql.session.timeZone": "UTC",
        "spark.sql.shuffle.partitions": "4",
        "spark.sql.streaming.metricsEnabled": "true",
        "spark.streaming.stopGracefullyOnShutdown": "true",
        "spark.ui.enabled": "true",
        "spark.ui.port": "4040",
        "spark.ui.prometheus.enabled": "true",
        "spark.metrics.conf.*.sink.prometheusServlet.class": (
            "org.apache.spark.metrics.sink.PrometheusServlet"
        ),
        "spark.metrics.conf.*.sink.prometheusServlet.path": "/metrics/prometheus",
        "spark.metrics.conf.master.sink.prometheusServlet.path": "/metrics/master/prometheus",
        "spark.metrics.conf.applications.sink.prometheusServlet.path": (
            "/metrics/applications/prometheus"
        ),
        "spark.cassandra.connection.host": settings.cassandra_host,
        "spark.cassandra.connection.port": str(settings.cassandra_port),
        "spark.cassandra.output.consistency.level": "LOCAL_ONE",
        "spark.hadoop.fs.s3a.endpoint": settings.aws_endpoint_url,
        "spark.hadoop.fs.s3a.endpoint.region": settings.aws_region,
        "spark.hadoop.fs.s3a.path.style.access": "true",
        "spark.hadoop.fs.s3a.access.key": settings.aws_access_key_id,
        "spark.hadoop.fs.s3a.secret.key": settings.aws_secret_access_key,
        "spark.hadoop.fs.s3a.connection.ssl.enabled": str(
            settings.aws_endpoint_url.startswith("https")
        ).lower(),
        "spark.hadoop.fs.s3a.aws.credentials.provider": (
            "org.apache.hadoop.fs.s3a.SimpleAWSCredentialsProvider"
        ),
        "spark.hadoop.fs.s3a.impl": "org.apache.hadoop.fs.s3a.S3AFileSystem",
    }
    if settings.spark_packages:
        conf["spark.jars.packages"] = settings.spark_packages
    return conf


def build_spark(settings: Settings, app_name: str = "vitalstream-stream-processor") -> SparkSession:
    """Create (or reuse) the SparkSession and quiet the JVM logger."""
    from pyspark.sql import SparkSession

    builder = SparkSession.builder
    for key, value in spark_conf(settings, app_name).items():
        builder = builder.config(key, value)
    session = builder.getOrCreate()
    session.sparkContext.setLogLevel("WARN")
    return session
