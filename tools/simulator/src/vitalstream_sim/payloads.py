"""Wire-format builders: device batch JSON, FHIR R4 Bundle, HL7 v2.5 ORU^R01."""

from __future__ import annotations

from collections.abc import Iterator, Sequence
from dataclasses import dataclass
from datetime import datetime
from typing import Any

from vitalstream_sim.catalog import CATALOG, Metric
from vitalstream_sim.ids import event_id, format_hl7_ts, format_iso, seeded_id

MAX_BATCH = 500
HL7_SENDING_APP = "VITALSTREAM-SIM"


@dataclass(frozen=True)
class Reading:
    """One measurement. ``id_seed`` overrides the derived event id (load mode)."""

    patient_id: str
    device_id: str
    metric: Metric
    value: float
    observed_at: datetime
    id_seed: str | None = None

    @property
    def event_id(self) -> str:
        """Stable idempotency key for this reading."""
        if self.id_seed is not None:
            return seeded_id(self.id_seed)
        return event_id(self.patient_id, self.metric, self.observed_at)


def chunk(readings: Sequence[Reading], size: int = MAX_BATCH) -> Iterator[Sequence[Reading]]:
    """Split into gateway-sized requests."""
    for start in range(0, len(readings), size):
        yield readings[start : start + size]


def _require_single_patient(readings: Sequence[Reading]) -> Reading:
    if not readings:
        raise ValueError("at least one reading is required")
    first = readings[0]
    if any(r.patient_id != first.patient_id or r.device_id != first.device_id for r in readings):
        raise ValueError("a message carries readings of one patient and one device")
    return first


def build_device_batch(readings: Sequence[Reading]) -> dict[str, Any]:
    """Body of ``POST /api/v1/vitals/batch`` (contracts/schemas/device-batch.schema.json)."""
    first = _require_single_patient(readings)
    return {
        "deviceId": first.device_id,
        "patientId": first.patient_id,
        "readings": [
            {
                "eventId": r.event_id,
                "metric": r.metric.value,
                "value": r.value,
                "observedAt": format_iso(r.observed_at),
            }
            for r in readings
        ],
    }


def _observation(reading: Reading) -> dict[str, Any]:
    spec = CATALOG[reading.metric]
    return {
        "resourceType": "Observation",
        "id": reading.event_id,
        "status": "final",
        "category": [
            {
                "coding": [
                    {
                        "system": "http://terminology.hl7.org/CodeSystem/observation-category",
                        "code": "vital-signs",
                    }
                ]
            }
        ],
        "code": {
            "coding": [{"system": "http://loinc.org", "code": spec.loinc, "display": spec.display}]
        },
        "subject": {"reference": f"Patient/{reading.patient_id}"},
        "device": {"reference": f"Device/{reading.device_id}"},
        "effectiveDateTime": format_iso(reading.observed_at),
        "valueQuantity": {
            "value": reading.value,
            "unit": spec.unit_display,
            "system": "http://unitsofmeasure.org",
            "code": spec.unit,
        },
    }


def build_fhir_bundle(readings: Sequence[Reading]) -> dict[str, Any]:
    """FHIR R4 ``Bundle`` of type ``batch`` with one vital-signs Observation per reading."""
    _require_single_patient(readings)
    return {
        "resourceType": "Bundle",
        "type": "batch",
        "entry": [
            {"resource": _observation(r), "request": {"method": "POST", "url": "Observation"}}
            for r in readings
        ],
    }


def hl7_control_id(readings: Sequence[Reading]) -> str:
    """MSH-10. HL7 has no event id, so the gateway derives ids from it: keep it deterministic so a
    retransmitted message deduplicates."""
    first = _require_single_patient(readings)
    earliest = min(r.observed_at for r in readings)
    return "SIM" + seeded_id(f"hl7|{first.patient_id}|{format_iso(earliest)}").replace("-", "")[:16]


def _obx(position: int, reading: Reading) -> str:
    spec = CATALOG[reading.metric]
    fields = [""] * 19
    fields[0] = "OBX"
    fields[1] = str(position)
    fields[2] = "NM"
    fields[3] = f"{spec.loinc}^{spec.display}^LN"
    fields[5] = format(reading.value, "g")
    fields[6] = spec.unit
    fields[11] = "F"
    fields[14] = format_hl7_ts(reading.observed_at)
    fields[18] = reading.device_id
    return "|".join(fields)


def build_hl7_oru(readings: Sequence[Reading]) -> str:
    """HL7 v2.5 ``ORU^R01``; segments are CR separated, device id in OBX-18."""
    first = _require_single_patient(readings)
    control = hl7_control_id(readings)
    sent = format_hl7_ts(min(r.observed_at for r in readings))
    segments = [
        f"MSH|^~\\&|{HL7_SENDING_APP}|SIM|VITALSTREAM|HOSP|{sent}||ORU^R01|{control}|P|2.5",
        f"PID|1||{first.patient_id}^^^HOSP^MR||SIMULATED^PATIENT",
        f"OBR|1||{control}|8716-3^Vital signs^LN|||{sent}",
        *(_obx(i, r) for i, r in enumerate(readings, start=1)),
    ]
    return "\r".join(segments) + "\r"
