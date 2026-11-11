"""Job entrypoint: wires settings, Redis, model, metrics endpoint and the streaming queries."""

from __future__ import annotations

import os
import sys
import time

import redis

from stream_processor.config import Settings
from stream_processor.listener import ProgressLogger
from stream_processor.logging_setup import configure_logging, get_logger
from stream_processor.metrics import Metrics, start_http_server
from stream_processor.pipeline import Deps, start_queries
from stream_processor.secret_guard import check_secrets
from stream_processor.spark_session import build_spark
from stream_processor.training import ensure_model


def main() -> None:
    """Run the stream processor until it is terminated (or drained, for availableNow)."""
    os.environ["TZ"] = "UTC"  # Spark timestamps surface in Python as naive local datetimes
    time.tzset()
    settings = Settings.from_env(os.environ)
    configure_logging(settings.service_name, settings.log_level)
    log = get_logger()
    check_secrets(settings)
    metrics = Metrics()
    queries: list[object] = []

    def health() -> dict[str, object]:
        active = [q for q in queries if getattr(q, "isActive", False)]
        done = settings.trigger_mode == "availableNow" and bool(queries)
        up = bool(active) or done
        return {"status": "UP" if up else "DOWN", "activeQueries": len(active)}

    start_http_server(settings.metrics_port, metrics, health)
    spark = build_spark(settings)
    spark.streams.addListener(ProgressLogger())
    model = ensure_model(spark, settings.model_dir)
    client = redis.Redis.from_url(settings.redis_url, decode_responses=True)
    deps = Deps(settings=settings, metrics=metrics, redis=client, model=model)
    started = start_queries(spark, deps)
    queries.extend(started)
    log.info(
        "stream processor started",
        topic=settings.vitals_topic,
        trigger=settings.trigger_mode,
        queries=[q.name for q in started],
    )
    try:
        for query in started:
            query.awaitTermination()
    except Exception:
        log.error("stream processor failed", exc_info=True)
        sys.exit(1)
    finally:
        spark.stop()
    log.info("stream processor stopped")


if __name__ == "__main__":
    main()
