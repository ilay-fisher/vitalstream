"""Contract tests: good samples pass, bad samples are rejected, and the rules from
docs/CONTRACTS.md section 3 (metric/unit/LOINC pairing, physiological range) hold."""

import copy

import pytest
from conftest import SAMPLES, SCHEMAS, load_json, make_validator

PREFIX_TO_SCHEMA = {
    "vital-sign-event": "vital-sign-event.schema.json",
    "alert-event": "alert-event.schema.json",
    "device-batch": "device-batch.schema.json",
}


def _samples(kind: str):
    out = []
    for path in sorted((SAMPLES / kind).glob("*.json")):
        for prefix, schema in PREFIX_TO_SCHEMA.items():
            if path.name.startswith(prefix):
                out.append(pytest.param(path, schema, id=path.name))
    return out


@pytest.mark.parametrize("path,schema", _samples("valid"))
def test_valid_samples_pass(path, schema):
    errors = list(make_validator(schema).iter_errors(load_json(path)))
    assert errors == [], [e.message for e in errors]


@pytest.mark.parametrize("path,schema", _samples("invalid"))
def test_invalid_samples_fail(path, schema):
    assert not make_validator(schema).is_valid(load_json(path))


def test_every_schema_has_valid_and_invalid_samples():
    for prefix in PREFIX_TO_SCHEMA:
        assert list((SAMPLES / "valid").glob(f"{prefix}*.json")), prefix
        assert list((SAMPLES / "invalid").glob(f"{prefix}*.json")), prefix


def test_schemas_declare_2020_12():
    for schema in SCHEMAS.glob("*.json"):
        assert load_json(schema)["$schema"] == "https://json-schema.org/draft/2020-12/schema"


METRICS = {
    "HEART_RATE": ("/min", "8867-4", 20, 250),
    "SPO2": ("%", "59408-5", 50, 100),
    "RESP_RATE": ("/min", "9279-1", 4, 60),
    "SYSTOLIC_BP": ("mm[Hg]", "8480-6", 50, 260),
    "DIASTOLIC_BP": ("mm[Hg]", "8462-4", 20, 160),
    "TEMPERATURE": ("Cel", "8310-5", 30, 43),
}


def _event(vital, metric, **overrides):
    unit, loinc, lo, hi = METRICS[metric]
    event = copy.deepcopy(vital)
    event.update(metric=metric, unit=unit, loinc=loinc, value=float((lo + hi) / 2))
    event.update(overrides)
    return event


@pytest.mark.parametrize("metric", METRICS)
def test_each_metric_accepts_range_edges(vital_validator, vital, metric):
    _, _, lo, hi = METRICS[metric]
    for value in (lo, hi):
        assert vital_validator.is_valid(_event(vital, metric, value=float(value)))


@pytest.mark.parametrize("metric", METRICS)
def test_each_metric_rejects_out_of_range(vital_validator, vital, metric):
    _, _, lo, hi = METRICS[metric]
    assert not vital_validator.is_valid(_event(vital, metric, value=lo - 0.5))
    assert not vital_validator.is_valid(_event(vital, metric, value=hi + 0.5))


@pytest.mark.parametrize("metric", METRICS)
def test_each_metric_rejects_wrong_unit_or_loinc(vital_validator, vital, metric):
    assert not vital_validator.is_valid(_event(vital, metric, unit="bogus"))
    assert not vital_validator.is_valid(_event(vital, metric, loinc="0000-0"))


@pytest.mark.parametrize(
    "field,value",
    [
        ("eventId", "not-a-uuid"),
        ("patientId", "P-1"),
        ("patientId", "X-0001"),
        ("deviceId", "dev-icu-0001"),
        ("deviceId", "DEV-ICU-1"),
        ("metric", "GLUCOSE"),
        ("value", "118"),
        ("observedAt", "yesterday"),
        ("ingestedAt", "2026-11-11"),
        ("source", "CARRIER_PIGEON"),
    ],
)
def test_vital_field_violations(vital_validator, vital, field, value):
    vital[field] = value
    assert not vital_validator.is_valid(vital)


@pytest.mark.parametrize("field", list(load_json(SAMPLES / "valid" / "vital-sign-event.json")))
def test_vital_requires_every_field(vital_validator, vital, field):
    del vital[field]
    assert not vital_validator.is_valid(vital)


def test_vital_rejects_unknown_property(vital_validator, vital):
    vital["extra"] = 1
    assert not vital_validator.is_valid(vital)


@pytest.mark.parametrize("source", ["FHIR", "HL7V2", "DEVICE_BATCH"])
def test_vital_sources(vital_validator, vital, source):
    vital["source"] = source
    assert vital_validator.is_valid(vital)


@pytest.mark.parametrize("atype", ["THRESHOLD", "TREND_ZSCORE", "NEWS2", "ML_ANOMALY"])
@pytest.mark.parametrize("severity", ["LOW", "MEDIUM", "HIGH", "CRITICAL"])
def test_alert_enums(alert_validator, alert, atype, severity):
    alert.update(type=atype, severity=severity)
    assert alert_validator.is_valid(alert)


@pytest.mark.parametrize(
    "field,value",
    [
        ("alertId", "nope"),
        ("patientId", "7"),
        ("type", "MAGIC"),
        ("severity", "SEVERE"),
        ("metric", "GLUCOSE"),
        ("value", "high"),
        ("score", "7"),
        ("message", ""),
        ("windowStart", "noon"),
        ("detectedAt", 1234),
    ],
)
def test_alert_field_violations(alert_validator, alert, field, value):
    alert[field] = value
    assert not alert_validator.is_valid(alert)


def test_alert_nullable_fields_accept_null_and_values(alert_validator, alert):
    alert.update(metric=None, value=None, score=None)
    assert alert_validator.is_valid(alert)
    alert.update(metric="HEART_RATE", value=131.0, score=3.2)
    assert alert_validator.is_valid(alert)


@pytest.mark.parametrize("field", list(load_json(SAMPLES / "valid" / "alert-event.json")))
def test_alert_requires_every_field(alert_validator, alert, field):
    del alert[field]
    assert not alert_validator.is_valid(alert)


def test_batch_limit_is_500(batch_validator):
    batch = load_json(SAMPLES / "valid" / "device-batch.json")
    reading = batch["readings"][0]
    batch["readings"] = [dict(reading) for _ in range(500)]
    assert batch_validator.is_valid(batch)
    batch["readings"].append(dict(reading))
    assert not batch_validator.is_valid(batch)


def test_batch_rejects_empty_readings(batch_validator):
    batch = load_json(SAMPLES / "valid" / "device-batch.json")
    batch["readings"] = []
    assert not batch_validator.is_valid(batch)
