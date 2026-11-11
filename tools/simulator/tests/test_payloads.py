from __future__ import annotations

from datetime import timedelta
from typing import Any

import jsonschema
import pytest

from tests.conftest import T0
from vitalstream_sim.catalog import CATALOG, Metric
from vitalstream_sim.payloads import (
    Reading,
    build_device_batch,
    build_fhir_bundle,
    build_hl7_oru,
    chunk,
)


def make_readings(
    n: int = 6, patient: str = "P-0001", device: str = "DEV-ICU-0001"
) -> list[Reading]:
    metrics = list(Metric)
    return [
        Reading(
            patient_id=patient,
            device_id=device,
            metric=metrics[i % 6],
            value=float(CATALOG[metrics[i % 6]].normal_min + 1),
            observed_at=T0 + timedelta(seconds=i // 6),
        )
        for i in range(n)
    ]


def test_device_batch_validates_against_contract_schema(
    device_batch_schema: dict[str, Any],
) -> None:
    payload = build_device_batch(make_readings(12))
    jsonschema.validate(payload, device_batch_schema, format_checker=jsonschema.FormatChecker())
    assert payload["deviceId"] == "DEV-ICU-0001"
    assert payload["patientId"] == "P-0001"
    assert len(payload["readings"]) == 12
    assert payload["readings"][0]["observedAt"] == "2026-11-11T20:15:30.123Z"


def test_device_batch_rejects_mixed_patients() -> None:
    mixed = [*make_readings(1), *make_readings(1, patient="P-0002", device="DEV-ICU-0002")]
    with pytest.raises(ValueError, match="one patient"):
        build_device_batch(mixed)


def test_event_ids_are_stable_across_rebuilds() -> None:
    assert build_device_batch(make_readings(6)) == build_device_batch(make_readings(6))


def test_chunk_respects_gateway_limit() -> None:
    parts = list(chunk(make_readings(1200), 500))
    assert [len(p) for p in parts] == [500, 500, 200]


def test_fhir_bundle_mirrors_contract_sample(fhir_bundle_sample: dict[str, Any]) -> None:
    bundle = build_fhir_bundle(make_readings(3))
    assert bundle["resourceType"] == "Bundle"
    assert bundle["type"] == "batch"
    sample_resource = fhir_bundle_sample["entry"][0]["resource"]
    for entry in bundle["entry"]:
        resource = entry["resource"]
        assert set(resource) == set(sample_resource)
        assert entry["request"] == {"method": "POST", "url": "Observation"}
        assert resource["subject"] == {"reference": "Patient/P-0001"}
        assert resource["device"] == {"reference": "Device/DEV-ICU-0001"}
        assert resource["valueQuantity"]["system"] == "http://unitsofmeasure.org"
    first = bundle["entry"][0]["resource"]
    assert first["code"]["coding"][0]["code"] == "8867-4"
    assert first["id"] == build_device_batch(make_readings(3))["readings"][0]["eventId"]
    assert first["effectiveDateTime"] == "2026-11-11T20:15:30.123Z"
    assert first["valueQuantity"]["code"] == "/min"


def test_fhir_bundle_covers_every_metric_loinc_and_unit() -> None:
    bundle = build_fhir_bundle(make_readings(6))
    got = {
        e["resource"]["code"]["coding"][0]["code"]: e["resource"]["valueQuantity"]["code"]
        for e in bundle["entry"]
    }
    assert got == {spec.loinc: spec.unit for spec in CATALOG.values()}


def hl7_segments(message: str) -> list[list[str]]:
    assert "\n" not in message
    return [seg.split("|") for seg in message.strip("\r").split("\r")]


def test_hl7_oru_structure() -> None:
    message = build_hl7_oru(make_readings(6))
    segments = hl7_segments(message)
    assert [s[0] for s in segments] == ["MSH", "PID", "OBR", *["OBX"] * 6]
    msh = segments[0]
    assert msh[1] == "^~\\&"
    assert msh[8] == "ORU^R01"
    assert msh[9].startswith("SIM")
    assert msh[11] == "2.5"
    assert segments[1][3].startswith("P-0001^^^")
    obx_hr = segments[3]
    assert obx_hr[1] == "1"
    assert obx_hr[2] == "NM"
    assert obx_hr[3].split("^")[0] == "8867-4"
    assert obx_hr[3].endswith("^LN")
    assert float(obx_hr[5]) == make_readings(1)[0].value
    assert obx_hr[6] == "/min"
    assert obx_hr[11] == "F"
    assert obx_hr[14] == "20261111201530.123+0000"
    assert obx_hr[18] == "DEV-ICU-0001"
    assert [s[1] for s in segments[3:]] == ["1", "2", "3", "4", "5", "6"]


def test_hl7_control_id_is_deterministic_for_retransmission() -> None:
    first = hl7_segments(build_hl7_oru(make_readings(6)))[0][9]
    again = hl7_segments(build_hl7_oru(make_readings(6)))[0][9]
    other = hl7_segments(build_hl7_oru(make_readings(6, patient="P-0002", device="DEV-ICU-0002")))[
        0
    ][9]
    assert first == again
    assert first != other
    assert len(first) <= 20


def test_hl7_requires_single_patient_and_non_empty() -> None:
    with pytest.raises(ValueError, match="one patient"):
        build_hl7_oru([*make_readings(1), *make_readings(1, patient="P-0002")])
    with pytest.raises(ValueError, match="at least one"):
        build_hl7_oru([])
