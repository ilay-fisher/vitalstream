"""Select the LLM provider from `LLM_PROVIDER`."""

from copilot.config import Settings
from copilot.llm.anthropic_provider import AnthropicProvider
from copilot.llm.fake import FakeLLM
from copilot.llm.types import LLMProvider


def build_llm(settings: Settings) -> LLMProvider:
    """`fake` (default, offline) or `anthropic` (needs ANTHROPIC_API_KEY)."""
    if settings.llm_provider == "fake":
        return FakeLLM()
    if settings.anthropic_api_key is None or not settings.anthropic_api_key.get_secret_value():
        raise ValueError("LLM_PROVIDER=anthropic requires ANTHROPIC_API_KEY")
    return AnthropicProvider(
        api_key=settings.anthropic_api_key.get_secret_value(),
        model=settings.llm_model,
        max_tokens=settings.llm_max_tokens,
    )
