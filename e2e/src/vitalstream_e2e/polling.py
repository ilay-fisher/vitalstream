"""Polling with a hard deadline, so no e2e step can hang the pipeline."""

from __future__ import annotations

import time
from collections.abc import Callable


class PollTimeout(AssertionError):
    """A condition did not become true in time (an AssertionError, so pytest reports a failure)."""


def poll_until[T](
    probe: Callable[[], T | None],
    *,
    timeout: float,
    interval: float,
    description: str,
    retry_on: tuple[type[BaseException], ...] = (),
    monotonic: Callable[[], float] = time.monotonic,
    sleep: Callable[[float], None] = time.sleep,
) -> T:
    """Call ``probe`` until it returns a truthy value or ``timeout`` seconds pass.

    Why ``retry_on``: services come up and settle at different speeds, so connection errors
    while polling mean "not yet" rather than "broken". Anything else is a real bug and propagates.
    The clock is injectable so the unit tests run instantly.
    """
    deadline = monotonic() + timeout
    last_error: BaseException | None = None
    while True:
        try:
            value = probe()
            if value:
                return value
            last_error = None
        except retry_on as error:
            last_error = error
        if monotonic() >= deadline:
            break
        sleep(interval)
    detail = f" (last error: {last_error})" if last_error else ""
    raise PollTimeout(f"timed out after {timeout:g}s waiting for {description}{detail}")
