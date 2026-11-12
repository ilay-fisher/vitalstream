"""Sepsis scenario for P-0003 through every hop of the platform, in pipeline order."""

from __future__ import annotations

import uuid
from datetime import datetime

import httpx
import pytest
from conftest import CLINICAL_API, COPILOT, PATIENT, Pipeline, log

from vitalstream_e2e.auth import DR_COHEN, NURSE_LEVI, OBSERVER, bearer
from vitalstream_e2e.polling import poll_until
from vitalstream_e2e.probes import ack_body, tool_calls
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
    """Clinicians see episodes: their ids differ from the detection ids on alerts.detected."""
    assert pipeline.alerts_tail is not None
    detections = [
        r.value for r in pipeline.alerts_tail.records if r.value.get("patientId") == PATIENT
    ]
    assert detections, f"no detection for {PATIENT} was seen on alerts.detected"
    detection_ids = {d["alertId"] for d in detections}
    detected_kinds = {(d["type"], d.get("metric")) for d in detections}
    # episodes that earlier runs left behind (lastSeenAt before this run started) are not ours
    ours = [
        a
        for a in alerts_for_patient
        if datetime.fromisoformat(str(a["lastSeenAt"])) >= pipeline.started_at
    ]

    assert ours, "clinical-api lists none of the episodes raised during this run"
    for alert in ours:
        assert alert["patientId"] == PATIENT
        assert alert["occurrences"] >= 1, alert  # type: ignore[operator]
        assert (
            alert["lastDetectionId"] in detection_ids
            or (alert["type"], alert["metric"]) in detected_kinds
        ), f"episode {alert['alertId']} matches no detection seen on alerts.detected"
    log(
        f"clinical-api: {len(ours)} episodes ({len(alerts_for_patient)} listed) "
        f"for {len(detections)} detections of {PATIENT}"
    )


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


def _open_alert(tokens: dict[str, str]) -> dict[str, object]:
    """Wait for an OPEN alert of the patient."""

    def fetch() -> dict[str, object] | None:
        page = httpx.get(
            f"{CLINICAL_API}/api/v1/alerts",
            params={"patientId": PATIENT, "status": "OPEN", "size": 50},
            headers=bearer(tokens[OBSERVER.sub]),
            timeout=10,
        ).json()
        for item in page["items"]:
            if item["status"] == "OPEN":
                return dict(item)
        return None

    return poll_until(
        fetch,
        timeout=60,
        interval=2,
        description=f"an OPEN alert for {PATIENT}",
        retry_on=(httpx.HTTPError, KeyError),
    )


def _ack_url(alert: dict[str, object]) -> str:
    return f"{CLINICAL_API}/api/v1/alerts/{alert['alertId']}/ack"


def test_07_no_token_is_401_with_a_bearer_challenge(pipeline: Pipeline) -> None:
    listing = httpx.get(f"{CLINICAL_API}/api/v1/alerts", timeout=10)
    health = httpx.get(f"{CLINICAL_API}/actuator/health", timeout=10)
    ack = httpx.post(f"{CLINICAL_API}/api/v1/alerts/{uuid.uuid4()}/ack", json={}, timeout=10)

    assert listing.status_code == 401, listing.text
    assert listing.headers["www-authenticate"].startswith("Bearer")
    assert listing.headers["content-type"].startswith("application/problem+json")
    assert ack.status_code == 401, ack.text
    assert health.status_code == 200, "health stays open"
    log("auth: GET /alerts and POST ack without a token -> 401; health -> 200")


def test_08_viewer_ack_is_403_and_the_alert_stays_open(
    pipeline: Pipeline, tokens: dict[str, str]
) -> None:
    alert = _open_alert(tokens)

    response = httpx.post(
        _ack_url(alert), json=ack_body(), headers=bearer(tokens[OBSERVER.sub]), timeout=10
    )

    assert response.status_code == 403, response.text
    assert response.headers["content-type"].startswith("application/problem+json")
    still = httpx.get(
        f"{CLINICAL_API}/api/v1/alerts/{alert['alertId']}",
        headers=bearer(tokens[OBSERVER.sub]),
        timeout=10,
    ).json()
    assert still["status"] == "OPEN"
    log(f"auth: VIEWER ack of {alert['alertId']} -> 403, alert still OPEN")


def test_09_clinician_ack_returns_200_with_the_token_name_then_409(
    pipeline: Pipeline, tokens: dict[str, str]
) -> None:
    alert = _open_alert(tokens)
    headers = bearer(tokens[NURSE_LEVI.sub])

    first = httpx.post(_ack_url(alert), json=ack_body(), headers=headers, timeout=10)
    second = httpx.post(_ack_url(alert), json=ack_body(), headers=headers, timeout=10)

    assert first.status_code == 200, first.text
    assert first.json()["status"] == "ACKNOWLEDGED"
    assert first.json()["acknowledgedBy"] == "Nurse Levi"
    assert second.status_code == 409, second.text
    log(f"ack: {first.status_code} acknowledgedBy={first.json()['acknowledgedBy']}, then 409")


def test_10_a_by_field_in_the_ack_body_is_ignored(
    pipeline: Pipeline, tokens: dict[str, str]
) -> None:
    first = _open_alert(tokens)
    response = httpx.post(
        _ack_url(first),
        json=ack_body(by="mallory"),
        headers=bearer(tokens[DR_COHEN.sub]),
        timeout=10,
    )

    assert response.status_code == 200, response.text
    assert response.json()["acknowledgedBy"] == "Dr. Cohen"
    log(f"ack: body by=mallory ignored, acknowledgedBy={response.json()['acknowledgedBy']}")


def test_11_copilot_chat_without_a_token_is_401(stack: None) -> None:
    response = httpx.post(
        f"{COPILOT}/api/v1/chat", json={"patientId": PATIENT, "message": "hello"}, timeout=15
    )

    assert response.status_code == 401, response.text
    assert response.headers["content-type"].startswith("application/problem+json")
    log("copilot: chat without a token -> 401")


def test_12_copilot_chat_acts_as_the_user_and_finishes(stack: None, tokens: dict[str, str]) -> None:
    events: list[tuple[str, dict[str, object]]] = []
    with httpx.stream(
        "POST",
        f"{COPILOT}/api/v1/chat",
        json={"patientId": PATIENT, "message": f"Summarise the current status of {PATIENT}."},
        headers={"Accept": "text/event-stream", **bearer(tokens[OBSERVER.sub])},
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
