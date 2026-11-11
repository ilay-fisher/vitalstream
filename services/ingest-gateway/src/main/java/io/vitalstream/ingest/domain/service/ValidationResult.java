package io.vitalstream.ingest.domain.service;

import io.vitalstream.ingest.domain.model.Rejection;
import io.vitalstream.ingest.domain.model.VitalReading;

/** Outcome of validating one {@code RawReading}: either a clean reading or the reason it failed. */
public sealed interface ValidationResult {

  /** The reading passed every rule. */
  record Valid(VitalReading reading) implements ValidationResult {}

  /** The reading failed a rule; the rest of its batch is unaffected. */
  record Invalid(Rejection rejection) implements ValidationResult {}
}
