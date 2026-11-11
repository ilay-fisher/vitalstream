"""Shared fixtures: schema loading and validators with format checking enabled."""

import json
from pathlib import Path

import pytest
from jsonschema import Draft202012Validator, FormatChecker

ROOT = Path(__file__).resolve().parent.parent
SCHEMAS = ROOT / "schemas"
SAMPLES = ROOT / "samples"


def load_json(path: Path):
    return json.loads(path.read_text(encoding="utf-8"))


def make_validator(schema_file: str) -> Draft202012Validator:
    schema = load_json(SCHEMAS / schema_file)
    Draft202012Validator.check_schema(schema)
    return Draft202012Validator(schema, format_checker=FormatChecker())


@pytest.fixture(scope="session")
def vital_validator():
    return make_validator("vital-sign-event.schema.json")


@pytest.fixture(scope="session")
def alert_validator():
    return make_validator("alert-event.schema.json")


@pytest.fixture(scope="session")
def batch_validator():
    return make_validator("device-batch.schema.json")


@pytest.fixture()
def vital():
    return load_json(SAMPLES / "valid" / "vital-sign-event.json")


@pytest.fixture()
def alert():
    return load_json(SAMPLES / "valid" / "alert-event.json")
