"""Provider selection and the Anthropic adapter (driven by a stub SDK client; no network)."""

from types import SimpleNamespace
from typing import Any

import pytest
from pydantic import SecretStr

from copilot.config import Settings
from copilot.llm.anthropic_provider import AnthropicProvider, to_api_messages
from copilot.llm.factory import build_llm
from copilot.llm.fake import FakeLLM
from copilot.llm.types import Message, TextDelta, ToolCall, ToolUse, Usage
from copilot.tools import CopilotTools


class _Stream:
    def __init__(self, events: list[Any], final: Any) -> None:
        self._events, self._final = events, final

    async def __aenter__(self) -> "_Stream":
        return self

    async def __aexit__(self, *exc: object) -> None:
        return None

    def __aiter__(self) -> "_Stream":
        self._it = iter(self._events)
        return self

    async def __anext__(self) -> Any:
        try:
            return next(self._it)
        except StopIteration:
            raise StopAsyncIteration from None

    async def get_final_message(self) -> Any:
        return self._final


class _Client:
    def __init__(self, stream: _Stream) -> None:
        self.calls: list[dict[str, Any]] = []
        self.messages = SimpleNamespace(stream=self._stream)
        self._s = stream

    def _stream(self, **kwargs: Any) -> _Stream:
        self.calls.append(kwargs)
        return self._s


def _final(blocks: list[Any]) -> Any:
    return SimpleNamespace(content=blocks, usage=SimpleNamespace(input_tokens=11, output_tokens=7))


def test_factory_returns_fake_by_default() -> None:
    assert isinstance(build_llm(Settings()), FakeLLM)


def test_factory_builds_anthropic_with_model_and_requires_key() -> None:
    settings = Settings(llm_provider="anthropic", anthropic_api_key=SecretStr("sk-test"))
    provider = build_llm(settings)
    assert isinstance(provider, AnthropicProvider)
    assert provider.model == "claude-sonnet-5-5"
    with pytest.raises(ValueError, match="ANTHROPIC_API_KEY"):
        build_llm(Settings(llm_provider="anthropic"))


def test_provider_repr_never_exposes_the_key() -> None:
    provider = AnthropicProvider(api_key="sk-super-secret", model="m", max_tokens=10)
    assert "sk-super-secret" not in repr(provider)
    assert "sk-super-secret" not in repr(Settings(anthropic_api_key=SecretStr("sk-super-secret")))


def test_message_conversion_groups_tool_results_and_keeps_tool_use_blocks() -> None:
    call = ToolCall("tu_1", "list_alerts", {"patient_id": "P-0001"})
    call2 = ToolCall("tu_2", "get_patient_vitals", {"patient_id": "P-0001"})
    api = to_api_messages(
        [
            Message("system", "sys"),
            Message("user", "hi"),
            Message("assistant", "checking", (call, call2)),
            Message("tool", '{"count": 0}', tool_call_id="tu_1", name="list_alerts"),
            Message("tool", '{"x": 1}', tool_call_id="tu_2", name="get_patient_vitals"),
        ]
    )
    assert [m["role"] for m in api] == ["user", "assistant", "user"]
    assert api[1]["content"][0] == {"type": "text", "text": "checking"}
    assert api[1]["content"][1]["type"] == "tool_use"
    assert api[1]["content"][1]["id"] == "tu_1"
    results = api[2]["content"]
    assert [r["tool_use_id"] for r in results] == ["tu_1", "tu_2"]
    assert all(r["type"] == "tool_result" for r in results)


async def test_stream_yields_text_tool_use_and_usage() -> None:
    events = [
        SimpleNamespace(type="message_start"),
        SimpleNamespace(type="text", text="Hel"),
        SimpleNamespace(type="text", text="lo"),
    ]
    final = _final(
        [
            SimpleNamespace(type="text", text="Hello"),
            SimpleNamespace(type="tool_use", id="tu_9", name="compute_news2", input={"rr": 16}),
        ]
    )
    client = _Client(_Stream(events, final))
    provider = AnthropicProvider(api_key="k", model="claude-test", max_tokens=256, client=client)

    out = [
        e
        async for e in provider.stream(
            [Message("system", "S"), Message("user", "q")], CopilotTools.SPECS
        )
    ]

    assert out[:2] == [TextDelta("Hel"), TextDelta("lo")]
    assert out[2] == ToolUse(ToolCall("tu_9", "compute_news2", {"rr": 16}))
    assert out[3] == Usage(11, 7)
    sent = client.calls[0]
    assert sent["model"] == "claude-test"
    assert sent["system"] == "S"
    assert sent["max_tokens"] == 256
    assert {t["name"] for t in sent["tools"]} == {s.name for s in CopilotTools.SPECS}
    assert sent["tools"][0]["input_schema"]["type"] == "object"
