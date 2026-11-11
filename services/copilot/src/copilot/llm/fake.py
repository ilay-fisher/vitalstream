"""Deterministic, scripted "LLM" for demos and tests (the default provider).

It behaves like a tool-calling model without any network access: it detects a patient id, asks
for vitals and alerts, computes NEWS2 from the *retrieved* vitals only, searches the guidelines
and finally writes a templated answer with numbered citations.  Because it never invents
values it is also a faithful illustration of the system prompt's safety rules.
"""

import json
import re
from collections.abc import AsyncIterator, Sequence
from typing import Any

from copilot.llm.types import LLMEvent, Message, TextDelta, ToolCall, ToolUse, Usage
from copilot.rag.chunker import estimate_tokens
from copilot.tools import ToolSpec

_PATIENT = re.compile(r"\bP-\d{4,}\b")
_CONTEXT = re.compile(r"Patient context: (P-\d{4,})")
_WORD = re.compile(r"\S+\s*")
_NEWS2_INPUTS = {
    "HEART_RATE": "hr",
    "SPO2": "spo2",
    "RESP_RATE": "rr",
    "SYSTOLIC_BP": "sbp",
    "TEMPERATURE": "temp",
}
_LABELS = {
    "HEART_RATE": "Heart rate",
    "SPO2": "SpO2",
    "RESP_RATE": "Respiratory rate",
    "SYSTOLIC_BP": "Systolic BP",
    "DIASTOLIC_BP": "Diastolic BP",
    "TEMPERATURE": "Temperature",
}
_OPEN = "<untrusted_reference_material>"
_CLOSE = "</untrusted_reference_material>"
_DISCLAIMER = (
    "Decision support only. This is not a diagnosis; confirm with the bedside assessment "
    "and your local protocol."
)


class FakeLLM:
    """Scripted provider selected with ``LLM_PROVIDER=fake``."""

    name = "fake"

    async def stream(
        self, messages: Sequence[Message], tools: Sequence[ToolSpec]
    ) -> AsyncIterator[LLMEvent]:
        """Decide the next step from the conversation state and stream it."""
        turn = self._current_turn(messages)
        results = {m.name: _parse(m.content) for m in turn if m.role == "tool" and m.name}
        patient_id = self._patient_id(messages)
        user_text = next((m.content for m in reversed(messages) if m.role == "user"), "")

        calls = self._next_calls(user_text, patient_id, results)
        if calls:
            for call in calls:
                yield ToolUse(call)
        else:
            async for word in _words(self._answer(patient_id, user_text, results)):
                yield TextDelta(word)
        prompt_tokens = sum(estimate_tokens(m.content) for m in messages)
        yield Usage(prompt_tokens, 0)

    # -- planning ---------------------------------------------------------------------------

    @staticmethod
    def _current_turn(messages: Sequence[Message]) -> list[Message]:
        last_user = max((i for i, m in enumerate(messages) if m.role == "user"), default=-1)
        return list(messages[last_user + 1 :])

    @staticmethod
    def _patient_id(messages: Sequence[Message]) -> str | None:
        for message in reversed(messages):
            if message.role == "user" and (found := _PATIENT.findall(message.content)):
                return str(found[-1])
        for message in messages:
            if message.role == "system" and (match := _CONTEXT.search(message.content)):
                return match.group(1)
        return None

    def _next_calls(
        self, user_text: str, patient_id: str | None, results: dict[str, Any]
    ) -> list[ToolCall]:
        if not results:
            if patient_id is None:
                return [ToolCall("call_search", "search_guidelines", {"query": user_text, "k": 3})]
            return [
                ToolCall(
                    "call_vitals", "get_patient_vitals", {"patient_id": patient_id, "minutes": 60}
                ),
                ToolCall("call_alerts", "list_alerts", {"patient_id": patient_id}),
            ]
        if "search_guidelines" in results or patient_id is None:
            return []
        calls: list[ToolCall] = []
        news2_args = _news2_arguments(results.get("get_patient_vitals"))
        if news2_args:
            calls.append(ToolCall("call_news2", "compute_news2", news2_args))
        query = _guideline_query(results.get("get_patient_vitals"))
        calls.append(ToolCall("call_guidelines", "search_guidelines", {"query": query, "k": 3}))
        return calls

    # -- answering --------------------------------------------------------------------------

    def _answer(self, patient_id: str | None, user_text: str, results: dict[str, Any]) -> str:
        refs = _references(results.get("search_guidelines"))
        if patient_id is None:
            return _general_answer(user_text, refs)
        lines = [_DISCLAIMER, ""]
        vitals = results.get("get_patient_vitals") or {}
        alerts = results.get("list_alerts") or {}
        news2 = results.get("compute_news2")

        lines.append(f"**{patient_id}: deterioration check**")
        lines.append(_assessment(vitals, alerts, news2))
        lines.append("")
        lines.extend(_vitals_section(patient_id, vitals))
        lines.extend(_alerts_section(alerts))
        if news2 and "total" in news2:
            lines.extend(_news2_section(news2, refs))
        elif "latest" in vitals:
            missing = sorted(set(_NEWS2_INPUTS) - set(vitals["latest"]))
            if missing:
                lines.append(
                    f"NEWS2 not computed: missing vitals {', '.join(missing)}. "
                    "I will not estimate values that were not measured."
                )
        lines.extend(_guideline_section(refs))
        return "\n".join(lines)


def _parse(content: str) -> Any:
    if content.startswith(_OPEN):
        content = content.removeprefix(_OPEN).removesuffix(_CLOSE).split("\n", 2)[-1]
    try:
        return json.loads(content)
    except json.JSONDecodeError:
        return {"error": content}


async def _words(text: str) -> AsyncIterator[str]:
    for match in _WORD.finditer(text):
        yield match.group(0)


def _news2_arguments(vitals: Any) -> dict[str, float] | None:
    latest = vitals.get("latest") if isinstance(vitals, dict) else None
    if not latest or any(metric not in latest for metric in _NEWS2_INPUTS):
        return None
    return {arg: float(latest[metric]["value"]) for metric, arg in _NEWS2_INPUTS.items()}


def _guideline_query(vitals: Any) -> str:
    terms = ["NEWS2 escalation"]
    latest = vitals.get("latest") if isinstance(vitals, dict) else None
    if not latest:
        return "NEWS2 escalation monitoring observations"

    def value(metric: str) -> float | None:
        return latest[metric]["value"] if metric in latest else None

    hr, spo2, sbp, temp, rr = (
        value("HEART_RATE"),
        value("SPO2"),
        value("SYSTOLIC_BP"),
        value("TEMPERATURE"),
        value("RESP_RATE"),
    )
    if hr is not None and hr > 100:
        terms.append("tachycardia")
    if spo2 is not None and spo2 < 94:
        terms.append("hypoxaemia low oxygen saturation")
    if sbp is not None and sbp <= 100:
        terms.append("hypotension")
    if temp is not None and temp >= 38.0:
        terms.append("fever sepsis")
    if rr is not None and rr > 20:
        terms.append("respiratory rate")
    return " ".join(terms)


def _references(search: Any) -> list[dict[str, Any]]:
    if not isinstance(search, dict):
        return []
    return [r for r in search.get("results", []) if isinstance(r, dict) and r.get("ref")]


def _snippet(content: str, limit: int = 200) -> str:
    body = content.split("\n\n", 1)[-1]
    body = re.sub(r"[#>*`|\-]+", " ", body)
    flat = " ".join(body.split())
    return flat if len(flat) <= limit else flat[: limit - 1].rstrip() + "…"


def _general_answer(user_text: str, refs: list[dict[str, Any]]) -> str:
    if not refs:
        return (
            f"{_DISCLAIMER}\n\nI could not find guideline passages relevant to "
            f'"{user_text.strip()}" in the corpus.'
        )
    lines = [_DISCLAIMER, "", "From the guideline corpus:"]
    lines += [f"- [{r['ref']}] {r['title']}: {_snippet(r['content'])}" for r in refs]
    return "\n".join(lines)


def _assessment(vitals: dict[str, Any], alerts: dict[str, Any], news2: Any) -> str:
    if "error" in vitals or "latest" not in vitals:
        reason = vitals.get("error", "no data")
        return (
            f"I could not retrieve live vitals ({reason}). I will not guess values, "
            "so I cannot say whether the patient is deteriorating."
        )
    open_alerts = _open_alerts(alerts)
    worst = max((a.get("severity") for a in open_alerts), key=_severity_rank, default=None)
    if news2 and "total" in news2:
        risk, total = news2["risk"], news2["total"]
        if risk in ("HIGH", "MEDIUM") or worst in ("CRITICAL", "HIGH"):
            verdict = "Yes, the data point to clinical deterioration."
        elif risk == "LOW_MEDIUM":
            verdict = "Possible early deterioration: a single parameter is in the red band."
        else:
            verdict = "No evidence of deterioration in the retrieved data."
        return f"{verdict} NEWS2 is {total} ({risk.replace('_', '-')} risk)."
    if not vitals["latest"]:
        return "No vitals were returned for the last hour, so I cannot assess deterioration."
    return "Partial data only, so no NEWS2 score; see the vitals below."


def _severity_rank(severity: str | None) -> int:
    return ["LOW", "MEDIUM", "HIGH", "CRITICAL"].index(severity) if severity else -1


def _open_alerts(alerts: dict[str, Any]) -> list[dict[str, Any]]:
    return [a for a in alerts.get("alerts", []) if a.get("status") in (None, "OPEN")]


def _vitals_section(patient_id: str, vitals: dict[str, Any]) -> list[str]:
    latest = vitals.get("latest")
    if not latest:
        return []
    lines = [f"Latest vitals for {patient_id} (last {vitals.get('window_minutes', 60)} min):"]
    for metric, entry in latest.items():
        stat = vitals.get("stats", {}).get(metric, {})
        window = f" (window {stat['min']:g}-{stat['max']:g}, avg {stat['avg']:g})" if stat else ""
        lines.append(
            f"- {_LABELS.get(metric, metric)}: {entry['value']:g} {entry.get('unit') or ''}{window}"
        )
    lines.append("")
    return lines


def _alerts_section(alerts: dict[str, Any]) -> list[str]:
    if "error" in alerts:
        return [f"Alerts: could not retrieve ({alerts['error']}).", ""]
    open_alerts = _open_alerts(alerts)
    if not open_alerts:
        return ["No open alerts for this patient.", ""]
    lines = [f"Open alerts ({len(open_alerts)}):"]
    lines += [f"- {a['severity']} {a['type']}: {a['message']}" for a in open_alerts[:5]]
    lines.append("")
    return lines


def _news2_section(news2: dict[str, Any], refs: list[dict[str, Any]]) -> list[str]:
    parts = ", ".join(f"{k} {v}" for k, v in news2["components"].items() if v)
    cite = f" [{refs[0]['ref']}]" if refs else ""
    return [
        f"NEWS2 breakdown: {parts or 'all parameters 0'} (total {news2['total']}).",
        f"Suggested response per NEWS2 chart: {news2['response']}{cite}",
        "",
    ]


def _guideline_section(refs: list[dict[str, Any]]) -> list[str]:
    if not refs:
        return []
    lines = ["Guideline support:"]
    lines += [f"- [{r['ref']}] {r['title']}: {_snippet(r['content'])}" for r in refs]
    return lines
