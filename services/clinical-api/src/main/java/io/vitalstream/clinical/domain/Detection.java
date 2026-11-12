package io.vitalstream.clinical.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One stream-processor detection for a patient, type, metric and 1-minute window (CONTRACTS section
 * 3.2). Detections are not shown to clinicians; they feed {@link Alert episodes}.
 */
public record Detection(
    UUID id,
    String patientId,
    AlertType type,
    Severity severity,
    String metric,
    Double value,
    Double score,
    String message,
    Instant windowStart,
    Instant windowEnd,
    Instant detectedAt) {

  public Detection {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(patientId, "patientId");
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(severity, "severity");
    Objects.requireNonNull(windowStart, "windowStart");
    Objects.requireNonNull(windowEnd, "windowEnd");
    Objects.requireNonNull(detectedAt, "detectedAt");
  }
}
