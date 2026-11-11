"""Sanity checks for the FHIR / HL7 v2.5 samples other services reuse as fixtures."""

import json

from conftest import SAMPLES

KNOWN_LOINC = {"8867-4", "59408-5", "9279-1", "8480-6", "8462-4", "8310-5"}


def _load(name):
    return json.loads((SAMPLES / name).read_text(encoding="utf-8"))


def test_fhir_observation_shape():
    obs = _load("fhir-observation.json")
    assert obs["resourceType"] == "Observation"
    coding = obs["code"]["coding"][0]
    assert coding["system"] == "http://loinc.org" and coding["code"] in KNOWN_LOINC
    assert obs["subject"]["reference"].startswith("Patient/P-")
    assert obs["device"]["reference"].startswith("Device/DEV-")
    assert "valueQuantity" in obs and obs["effectiveDateTime"].endswith("Z")


def test_fhir_bundle_is_batch_of_observations():
    bundle = _load("fhir-bundle.json")
    assert bundle["resourceType"] == "Bundle" and bundle["type"] == "batch"
    assert len(bundle["entry"]) >= 2
    for entry in bundle["entry"]:
        assert entry["resource"]["resourceType"] == "Observation"
        assert entry["resource"]["code"]["coding"][0]["code"] in KNOWN_LOINC


def test_hl7_oru_r01_structure():
    text = (SAMPLES / "hl7-oru-r01.hl7").read_text(encoding="utf-8")
    segments = [s for s in text.replace("\r\n", "\r").replace("\n", "\r").split("\r") if s]
    assert segments[0].startswith("MSH|^~\\&|")
    msh = segments[0].split("|")
    assert msh[8] == "ORU^R01" and msh[11] == "2.5"
    assert any(s.startswith("PID|") for s in segments)
    obx = [s.split("|") for s in segments if s.startswith("OBX|")]
    assert len(obx) >= 2
    for fields in obx:
        assert fields[2] == "NM"
        assert fields[3].split("^")[0] in KNOWN_LOINC
