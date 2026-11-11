"""Prometheus business metrics (module-level so repeated app construction reuses them)."""

from prometheus_client import Counter, Histogram

CHAT_REQUESTS = Counter("copilot_chat_requests_total", "Chat requests handled", ["status"])
TOOL_CALLS = Counter("copilot_tool_calls_total", "Agent/MCP tool invocations", ["tool", "status"])
RETRIEVAL_LATENCY = Histogram(
    "copilot_retrieval_latency_seconds",
    "Hybrid retrieval wall time",
    buckets=(0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5),
)
LLM_LATENCY = Histogram(
    "copilot_llm_latency_seconds",
    "LLM generation wall time per call",
    ["provider"],
    buckets=(0.01, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10, 30, 60),
)
LLM_TOKENS = Counter("copilot_llm_tokens_total", "LLM tokens consumed", ["provider", "direction"])
