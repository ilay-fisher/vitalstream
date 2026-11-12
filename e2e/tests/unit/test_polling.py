import pytest

from vitalstream_e2e.polling import PollTimeout, poll_until


class FakeClock:
    """Deterministic clock whose sleep advances time, so no test ever waits."""

    def __init__(self) -> None:
        self.now = 0.0
        self.sleeps: list[float] = []

    def monotonic(self) -> float:
        return self.now

    def sleep(self, seconds: float) -> None:
        self.sleeps.append(seconds)
        self.now += seconds


def test_returns_first_truthy_value():
    clock = FakeClock()
    values = iter([None, [], "ready"])

    result = poll_until(
        lambda: next(values),
        timeout=10,
        interval=1,
        description="thing",
        monotonic=clock.monotonic,
        sleep=clock.sleep,
    )

    assert result == "ready"
    assert clock.sleeps == [1, 1]


def test_times_out_with_description_and_timeout():
    clock = FakeClock()

    with pytest.raises(PollTimeout) as excinfo:
        poll_until(
            lambda: 0,
            timeout=3,
            interval=1,
            description="alert for P-0003",
            monotonic=clock.monotonic,
            sleep=clock.sleep,
        )

    assert "alert for P-0003" in str(excinfo.value)
    assert "3" in str(excinfo.value)
    assert clock.now >= 3


def test_transient_errors_are_retried_until_deadline():
    clock = FakeClock()
    calls = {"n": 0}

    def flaky() -> str:
        calls["n"] += 1
        if calls["n"] < 3:
            raise ConnectionError("not yet")
        return "ok"

    result = poll_until(
        flaky,
        timeout=10,
        interval=1,
        description="flaky",
        retry_on=(ConnectionError,),
        monotonic=clock.monotonic,
        sleep=clock.sleep,
    )

    assert result == "ok"


def test_timeout_reports_last_error():
    clock = FakeClock()

    def always_fails() -> str:
        raise ConnectionError("refused")

    with pytest.raises(PollTimeout) as excinfo:
        poll_until(
            always_fails,
            timeout=2,
            interval=1,
            description="service",
            retry_on=(ConnectionError,),
            monotonic=clock.monotonic,
            sleep=clock.sleep,
        )

    assert "refused" in str(excinfo.value)


def test_unlisted_errors_propagate():
    clock = FakeClock()

    def boom() -> str:
        raise ValueError("bug")

    with pytest.raises(ValueError):
        poll_until(
            boom,
            timeout=2,
            interval=1,
            description="x",
            retry_on=(ConnectionError,),
            monotonic=clock.monotonic,
            sleep=clock.sleep,
        )
