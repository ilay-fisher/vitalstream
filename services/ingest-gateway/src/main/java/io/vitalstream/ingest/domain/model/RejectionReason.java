package io.vitalstream.ingest.domain.model;

/** Closed set of per-reading rejection reasons. */
public enum RejectionReason {
  INVALID_EVENT_ID,
  INVALID_PATIENT_ID,
  INVALID_DEVICE_ID,
  UNKNOWN_METRIC,
  LOINC_MISMATCH,
  UNIT_MISMATCH,
  MISSING_VALUE,
  OUT_OF_RANGE,
  INVALID_TIMESTAMP,
  FUTURE_TIMESTAMP,
  UNSUPPORTED_RESOURCE
}
