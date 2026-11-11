package io.vitalstream.clinical.application;

import io.vitalstream.clinical.domain.VitalMetric;
import java.time.Instant;

/** Validated request for historical vitals. {@code metric} null means all metrics. */
public record VitalsQuery(
    String patientId, VitalMetric metric, Instant from, Instant to, Resolution resolution) {

  /** Raw readings or one-minute aggregates. */
  public enum Resolution {
    RAW,
    MINUTE
  }
}
