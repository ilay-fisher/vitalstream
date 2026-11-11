"""In-memory per-session conversation memory with a TTL and hard size bounds."""

import secrets
import time
from collections import OrderedDict
from collections.abc import Callable

from copilot.llm.types import Message


class ConversationMemory:
    """Remembers recent user/assistant turns per ``sessionId``.

    Bounded three ways so an unauthenticated demo endpoint cannot exhaust memory: sessions
    expire after ``ttl_seconds`` of inactivity, at most ``max_sessions`` are kept (least
    recently used evicted) and each keeps at most ``max_messages`` messages.
    """

    def __init__(
        self,
        *,
        ttl_seconds: float,
        max_sessions: int,
        max_messages: int,
        clock: Callable[[], float] = time.monotonic,
    ) -> None:
        self._ttl = ttl_seconds
        self._max_sessions = max_sessions
        self._max_messages = max_messages
        self._clock = clock
        self._sessions: OrderedDict[str, tuple[float, list[Message]]] = OrderedDict()

    def __len__(self) -> int:
        self._purge()
        return len(self._sessions)

    def create(self) -> str:
        """Issue a new unguessable (128-bit) session id and register it."""
        session_id = secrets.token_hex(16)
        self.append(session_id, [])
        return session_id

    def has(self, session_id: str) -> bool:
        """True only for ids this memory issued that have not expired or been evicted."""
        self._purge()
        return session_id in self._sessions

    def get(self, session_id: str) -> list[Message]:
        """A copy of the session history (empty if unknown or expired)."""
        self._purge()
        entry = self._sessions.get(session_id)
        if entry is None:
            return []
        self._sessions.move_to_end(session_id)
        return list(entry[1])

    def append(self, session_id: str, messages: list[Message]) -> None:
        """Add messages to a session, refreshing its TTL and enforcing the bounds."""
        self._purge()
        history = [*self._sessions.get(session_id, (0.0, []))[1], *messages]
        history = history[-self._max_messages :]
        while history and history[0].role != "user":
            history.pop(0)
        self._sessions[session_id] = (self._clock() + self._ttl, history)
        self._sessions.move_to_end(session_id)
        while len(self._sessions) > self._max_sessions:
            self._sessions.popitem(last=False)

    def _purge(self) -> None:
        now = self._clock()
        expired = [sid for sid, (expires, _) in self._sessions.items() if expires <= now]
        for sid in expired:
            del self._sessions[sid]
