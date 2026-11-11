"""StreamingQueryListener that logs one JSON line per micro-batch."""

from __future__ import annotations

from typing import Any

from pyspark.sql.streaming.listener import StreamingQueryListener

from stream_processor.logging_setup import get_logger


def progress_fields(progress: Any) -> dict[str, Any]:
    """Extract the fields we log from a StreamingQueryProgress (attribute access, any object)."""
    duration = getattr(progress, "durationMs", None) or {}
    return {
        "query": getattr(progress, "name", None),
        "batchId": progress.batchId,
        "numInputRows": progress.numInputRows,
        "inputRowsPerSecond": round(float(progress.inputRowsPerSecond or 0.0), 2),
        "processedRowsPerSecond": round(float(progress.processedRowsPerSecond or 0.0), 2),
        "durationMs": duration.get("triggerExecution"),
    }


class ProgressLogger(StreamingQueryListener):
    """Log batch progress; failures of the listener must never affect the query."""

    def onQueryStarted(self, event: Any) -> None:  # noqa: N802
        """Log query start."""
        get_logger().info("query started", query=event.name, queryId=str(event.id))

    def onQueryProgress(self, event: Any) -> None:  # noqa: N802
        """Log batchId, rows, throughput and duration."""
        get_logger().info("batch progress", **progress_fields(event.progress))

    def onQueryIdle(self, event: Any) -> None:  # noqa: N802
        """Ignore idle ticks to keep the log quiet."""

    def onQueryTerminated(self, event: Any) -> None:  # noqa: N802
        """Log termination, with the exception text if the query failed."""
        log = get_logger()
        if event.exception:
            log.error("query terminated", queryId=str(event.id), error=event.exception)
        else:
            log.info("query terminated", queryId=str(event.id))
