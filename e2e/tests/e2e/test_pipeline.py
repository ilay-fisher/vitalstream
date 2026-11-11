"""Sepsis scenario for P-0003 through every hop of the platform, in pipeline order."""

from __future__ import annotations

import httpx
import pytest
from conftest import CLINICAL_API, COPILOT, PATIENT, Pipeline, log

from vitalstream_e2e.polling import poll_until
from vitalstream_e2e.probes import ack_body, find_open_alert, tool_calls
from vitalstream_e2e.sse import parse_sse

pytestmark = pytest.mark.e2e


def test_01_gateway_accepts_probe_with_202(pipeline: Pipeline) -> None:
    response = pipeline.probe_response
    assert response is not None
    assert response.status_code == 202, response.text
    assert response.json()["accepted"] == 1
    log(f"gateway: 202 {response.json()}")


def test_02_probe_event_reaches_vitals_raw(pipeline: Pipeline) -> None:
    assert pipeline.vitals_tail is not None
    record = pipeline.vitals_tail.wait_for(
        lambda r: r.value.get("eventId") == pipeline.probe_event_id,
        timeout=60,
        description=f"probe event {pipeline.probe_event_id}",
    )
    assert record.key == PATIENT
    assert record.value["metric"] == "HEART_RATE"
    assert record.value["patientId"] == PATIENT
    assert record.headers.get("correlationId"), "gateway must propagate correlationId to Kafka"
    log(
        f"vitals.raw: eventId {record.value['eventId']} "
        f"correlationId {record.headers['correlationId']}"
    )


def test_03_simulator_completed_without_gateway_errors(pipeline: Pipeline) -> None:
    assert "Traceback" not in pipeline.simulator_output, pipeline.simulator_output[-2000:]
    assert pipeline.vitals_tail is not None
    sepsis = [r for r in pipeline.vitals_tail.records if r.value.get("patientId") == PATIENT]
    assert len(sepsis) > 20, f"expected simulator readings for {PATIENT}, saw {len(sepsis)}"
    log(f"vitals.raw: {len(sepsis)} records for {PATIENT} during the run")


def test_04_stream_processor_raises_alert_on_alerts_detected(pipeline: Pipeline) -> None:
    assert pipeline.alerts_tail is not None
    record = pipeline.alerts_tail.wait_for(
        lambda r: r.value.get("patientId") == PATIENT,
        timeout=240,
        description=f"alert for {PATIENT}",
    )
    alert = record.value
    assert alert["severity"] in {"MEDIUM", "HIGH", "CRITICAL"}
    assert alert["type"] in {"THRESHOLD", "TREND_ZSCORE", "NEWS2", "ML_ANOMALY"}
    assert record.key == PATIENT
    log(
        f"alerts.detected: {alert['type']} {alert['severity']} "
        f"{alert['alertId']}: {alert['message']}"
    )


def test_05_clinical_api_lists_the_alert(
    pipeline: Pipeline, alerts_for_patient: list[dict[str, object]]
) -> None:
    assert pipeline.alerts_tail is not None
    detected_ids = {r.value["alertId"] for r in pipeline.alerts_tail.records}
    listed_ids = {a["alertId"] for a in alerts_for_patient}
    assert listed_ids & detected_ids, "clinical-api shows none of the alerts the processor raised"
    assert all(a["patientId"] == PATIENT for a in alerts_for_patient)
    log(f"clinical-api: {len(alerts_for_patient)} alerts for {PATIENT}")


def test_06_sse_stream_delivers_the_alert(pipeline: Pipeline) -> None:
    assert pipeline.sse is not None
    event = pipeline.sse.wait_for(
        lambda e: e.event == "alert" and e.json().get("patientId") == PATIENT,
        timeout=60,
        description=f"SSE alert for {PATIENT}",
    )
    view = event.json()
    assert view["status"] in {"OPEN", "ACKNOWLEDGED"}
    assert event.id and event.id.startswith(view["alertId"])
    log(f"sse: event alert id={event.id} severity={view['severity']}")


def test_07_ack_returns_200_then_409(pipeline: Pipeline) -> None:
    def open_alert() -> dict[str, object] | None:
        page = httpx.get(
            f"{CLINICAL_API}/api/v1/alerts",
            params={"patientId": PATIENT, "status": "OPEN", "size": 50},
            timeout=10,
        ).json()
        return find_open_alert(page)

    alert = poll_until(
        open_alert,
        timeout=60,
        interval=2,
        description=f"an OPEN alert for {PATIENT}",
        retry_on=(httpx.HTTPError,),
    )
    url = f"{CLINICAL_API}/api/v1/alerts/{alert['alertId']}/ack"

    first = httpx.post(url, json=ack_body("e2e"), timeout=10)
    second = httpx.post(url, json=ack_body("e2e"), timeout=10)

    assert first.status_code == 200, first.text
    assert first.json()["status"] == "ACKNOWLEDGED"
    assert first.json()["acknowledgedBy"] == "e2e"
    assert second.status_code == 409, second.text
    log(f"ack: first {first.status_code}, second {second.status_code} for {alert['alertId']}")


def test_08_copilot_chat_calls_clinical_api_tools_and_finishes(stack: None) -> None:
    events: list[tuple[str, dict[str, object]]] = []
    with httpx.stream(
        "POST",
        f"{COPILOT}/api/v1/chat",
        json={"patientId": PATIENT, "message": f"Summarise the current status of {PATIENT}."},
        headers={"Accept": "text/event-stream"},
        timeout=httpx.Timeout(10, read=90),
    ) as response:
        assert response.status_code == 200, response.read()
        for sse in parse_sse(response.iter_lines()):
            events.append((sse.event, sse.json()))
            if sse.event == "done":
                break

    names = [name for name, _ in events]
    assert "done" in names, f"stream ended without done: {names}"
    done = dict(events)["done"]
    assert not done.get("error"), done
    completed = tool_calls(events)  # type: ignore[arg-type]
    assert {"get_patient_vitals", "list_alerts"} & set(completed), completed
    log(f"copilot: events {sorted(set(names))}; tools ok {completed}; done {done}")
