import json

import pytest

from stream_processor.logging_setup import bind_correlation_id, configure_logging, get_logger


def _lines(capsys: pytest.CaptureFixture[str]) -> list[dict[str, object]]:
    out = capsys.readouterr().out.strip().splitlines()
    return [json.loads(line) for line in out]


def test_emits_json_with_service_timestamp_level_message(
    capsys: pytest.CaptureFixture[str],
) -> None:
    configure_logging("stream-processor", "INFO")
    get_logger().info("alert raised", alertId="a-1")
    (line,) = _lines(capsys)
    assert line["service"] == "stream-processor"
    assert line["level"] == "info"
    assert line["message"] == "alert raised"
    assert line["alertId"] == "a-1"
    assert str(line["timestamp"]).endswith("Z")
    assert "correlationId" not in line


def test_correlation_id_is_attached_until_cleared(capsys: pytest.CaptureFixture[str]) -> None:
    configure_logging("stream-processor", "INFO")
    bind_correlation_id("corr-1")
    get_logger().info("with")
    bind_correlation_id(None)
    get_logger().info("without")
    with_corr, without = _lines(capsys)
    assert with_corr["correlationId"] == "corr-1"
    assert "correlationId" not in without


def test_level_filtering(capsys: pytest.CaptureFixture[str]) -> None:
    configure_logging("stream-processor", "WARNING")
    get_logger().info("hidden")
    get_logger().warning("shown")
    assert [line["message"] for line in _lines(capsys)] == ["shown"]


def test_unknown_level_falls_back_to_info(capsys: pytest.CaptureFixture[str]) -> None:
    configure_logging("stream-processor", "LOUD")
    get_logger().debug("hidden")
    get_logger().info("shown")
    assert [line["message"] for line in _lines(capsys)] == ["shown"]


def test_exception_is_rendered(capsys: pytest.CaptureFixture[str]) -> None:
    configure_logging("stream-processor", "INFO")
    try:
        raise RuntimeError("boom")
    except RuntimeError:
        get_logger().error("failed", exc_info=True)
    (line,) = _lines(capsys)
    assert "RuntimeError: boom" in str(line["exception"])
