"""Per-session conversation memory: TTL and size bounds."""

from copilot.agent.memory import ConversationMemory
from copilot.llm.types import Message


class Clock:
    def __init__(self) -> None:
        self.now = 1000.0

    def __call__(self) -> float:
        return self.now


def _m(text: str, role: str = "user") -> Message:
    return Message(role=role, content=text)  # type: ignore[arg-type]


def test_history_is_returned_in_order_per_session() -> None:
    memory = ConversationMemory(ttl_seconds=60, max_sessions=10, max_messages=10)
    memory.append("a", [_m("hi"), _m("hello", "assistant")])
    memory.append("b", [_m("other")])
    assert [m.content for m in memory.get("a")] == ["hi", "hello"]
    assert [m.content for m in memory.get("b")] == ["other"]
    assert memory.get("missing") == []


def test_sessions_expire_after_ttl() -> None:
    clock = Clock()
    memory = ConversationMemory(ttl_seconds=60, max_sessions=10, max_messages=10, clock=clock)
    memory.append("a", [_m("hi")])
    clock.now += 59
    assert memory.get("a")
    clock.now += 61
    assert memory.get("a") == []
    assert len(memory) == 0


def test_activity_refreshes_ttl() -> None:
    clock = Clock()
    memory = ConversationMemory(ttl_seconds=60, max_sessions=10, max_messages=10, clock=clock)
    memory.append("a", [_m("1")])
    clock.now += 50
    memory.append("a", [_m("2")])
    clock.now += 50
    assert len(memory.get("a")) == 2


def test_oldest_session_evicted_when_full() -> None:
    memory = ConversationMemory(ttl_seconds=60, max_sessions=2, max_messages=10)
    memory.append("a", [_m("1")])
    memory.append("b", [_m("2")])
    memory.get("a")  # touch a so b becomes least recently used
    memory.append("c", [_m("3")])
    assert memory.get("b") == []
    assert memory.get("a") and memory.get("c")
    assert len(memory) == 2


def test_history_is_trimmed_to_max_messages_and_starts_on_a_user_turn() -> None:
    memory = ConversationMemory(ttl_seconds=60, max_sessions=10, max_messages=4)
    for i in range(5):
        memory.append("a", [_m(f"q{i}"), _m(f"a{i}", "assistant")])
    history = memory.get("a")
    assert len(history) <= 4
    assert history[0].role == "user"
    assert history[-1].content == "a4"


def test_get_returns_a_copy() -> None:
    memory = ConversationMemory(ttl_seconds=60, max_sessions=10, max_messages=10)
    memory.append("a", [_m("1")])
    memory.get("a").clear()
    assert len(memory.get("a")) == 1
