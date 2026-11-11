"""Provider-neutral conversation types and the :class:`LLMProvider` port."""

from collections.abc import AsyncIterator, Sequence
from dataclasses import dataclass, field
from typing import Any, Literal, Protocol

from copilot.tools import ToolSpec

Role = Literal["system", "user", "assistant", "tool"]


@dataclass(frozen=True, slots=True)
class ToolCall:
    """A request from the model to run a tool."""

    id: str
    name: str
    arguments: dict[str, Any]


@dataclass(frozen=True, slots=True)
class Message:
    """One conversation message. Tool results carry ``tool_call_id`` and the tool ``name``."""

    role: Role
    content: str = ""
    tool_calls: tuple[ToolCall, ...] = field(default_factory=tuple)
    tool_call_id: str | None = None
    name: str | None = None


@dataclass(frozen=True, slots=True)
class TextDelta:
    """A streamed fragment of assistant text."""

    text: str


@dataclass(frozen=True, slots=True)
class ToolUse:
    """The model asked for a tool call."""

    call: ToolCall


@dataclass(frozen=True, slots=True)
class Usage:
    """Token accounting for one model call."""

    input_tokens: int
    output_tokens: int


LLMEvent = TextDelta | ToolUse | Usage


class LLMProvider(Protocol):
    """A chat model that can stream text and request tool calls."""

    name: str

    def stream(
        self, messages: Sequence[Message], tools: Sequence[ToolSpec]
    ) -> AsyncIterator[LLMEvent]:
        """Stream the next assistant turn for ``messages`` given the available ``tools``."""
        ...
