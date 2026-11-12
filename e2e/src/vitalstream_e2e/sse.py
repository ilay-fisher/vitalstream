"""Server-Sent Events parsing and a background collector.

The clinical-api live feed has no replay on reconnect, so the e2e run must already be listening
when the alert is raised; the collector keeps every event so later assertions can look back.
"""

from __future__ import annotations

import json
import threading
from collections.abc import Callable, Iterable, Iterator
from dataclasses import dataclass
from typing import Any

from vitalstream_e2e.polling import poll_until


@dataclass(frozen=True)
class SseEvent:
    """One dispatched SSE event."""

    event: str
    id: str | None
    data: str

    def json(self) -> Any:
        """Decode ``data`` as JSON (every VitalStream stream sends JSON payloads)."""
        return json.loads(self.data)


def parse_sse(lines: Iterable[str]) -> Iterator[SseEvent]:
    """Yield events from decoded SSE lines; an event is dispatched by its blank line."""
    event, event_id = "message", None
    data: list[str] = []
    for line in lines:
        if line == "":
            if data:
                yield SseEvent(event, event_id, "\n".join(data))
            event, event_id, data = "message", None, []
        elif line.startswith(":"):
            continue
        else:
            field, _, value = line.partition(":")
            value = value.removeprefix(" ")
            if field == "event":
                event = value
            elif field == "id":
                event_id = value
            elif field == "data":
                data.append(value)


class SseCollector:
    """Consume an event stream on a daemon thread and let tests wait for matching events."""

    def __init__(self, stream: Callable[[], Iterator[SseEvent]]) -> None:
        self._stream = stream
        self._events: list[SseEvent] = []
        self._lock = threading.Lock()
        self._thread = threading.Thread(target=self._run, daemon=True)
        self.error: BaseException | None = None

    def add(self, event: SseEvent) -> None:
        with self._lock:
            self._events.append(event)

    def start(self) -> None:
        self._thread.start()

    def stop(self) -> None:
        """Daemon thread dies with the process; nothing to join on a blocking socket read."""

    @property
    def events(self) -> list[SseEvent]:
        with self._lock:
            return list(self._events)

    def wait_for(
        self,
        predicate: Callable[[SseEvent], bool],
        *,
        timeout: float,
        description: str,
        interval: float = 0.25,
    ) -> SseEvent:
        """Block until an event matching ``predicate`` was seen (now or earlier)."""

        def probe() -> SseEvent | None:
            if self.error:
                raise _StreamFailed(str(self.error))
            return next((e for e in self.events if predicate(e)), None)

        return poll_until(
            probe,
            timeout=timeout,
            interval=interval,
            description=description,
            retry_on=(_StreamFailed,),
        )

    def _run(self) -> None:
        try:
            for event in self._stream():
                self.add(event)
        except Exception as error:
            self.error = error


class _StreamFailed(Exception):
    """Internal: carries the collector's stream error into the poll timeout message."""
