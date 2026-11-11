"""Shared fixtures: contract schemas and a fixed clock."""

from __future__ import annotations

import json
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

import pytest

CONTRACTS = Path(__file__).resolve().parents[3] / "contracts"
T0 = datetime(2026, 11, 11, 20, 15, 30, 123000, tzinfo=UTC)


@pytest.fixture(scope="session")
def device_batch_schema() -> dict[str, Any]:
    return json.loads((CONTRACTS / "schemas" / "device-batch.schema.json").read_text())  # type: ignore[no-any-return]


@pytest.fixture(scope="session")
def fhir_bundle_sample() -> dict[str, Any]:
    return json.loads((CONTRACTS / "samples" / "fhir-bundle.json").read_text())  # type: ignore[no-any-return]
