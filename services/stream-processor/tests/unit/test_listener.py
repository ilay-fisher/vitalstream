import json
from types import SimpleNamespace

import pytest

from stream_processor.listener import ProgressLogger, progress_fields
from stream_processor.logging_setup import configure_logging


def _progress() -> SimpleNamespace:
    return SimpleNamespace(
        name="window-detect",
        batchId=7,
        numInputRows=120,
        inputRowsPerSecond=24.0123,
        processedRowsPerSecond=60.5,
        durationMs={"triggerExecution": 1980, "addBatch": 1500},
    )


def test_progress_fields() -> None:
    assert progress_fields(_progress()) == {
        "query": "window-detect",
        "batchId": 7,
        "numInputRows": 120,
        "inputRowsPerSecond": 24.01,
        "processedRowsPerSecond": 60.5,
        "durationMs": 1980,
    }


def test_listener_logs_json_progress(capsys: pytest.CaptureFixture[str]) -> None:
    configure_logging("stream-processor", "INFO")
    ProgressLogger().onQueryProgress(SimpleNamespace(progress=_progress()))
    line = json.loads(capsys.readouterr().out.strip())
    assert line["message"] == "batch progress"
    assert line["batchId"] == 7 and line["durationMs"] == 1980


def test_listener_started_terminated_idle(capsys: pytest.CaptureFixture[str]) -> None:
    configure_logging("stream-processor", "INFO")
    listener = ProgressLogger()
    listener.onQueryStarted(SimpleNamespace(name="q", id="abc"))
    listener.onQueryIdle(SimpleNamespace())
    listener.onQueryTerminated(SimpleNamespace(id="abc", exception=None))
    listener.onQueryTerminated(SimpleNamespace(id="abc", exception="boom"))
    lines = [json.loads(x) for x in capsys.readouterr().out.strip().splitlines()]
    assert [x["message"] for x in lines] == [
        "query started",
        "query terminated",
        "query terminated",
    ]
    assert lines[2]["error"] == "boom" and lines[2]["level"] == "error"
