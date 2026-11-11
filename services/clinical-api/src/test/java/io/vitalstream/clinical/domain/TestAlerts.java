package io.vitalstream.clinical.domain;

import java.time.Instant;
import java.util.UUID;

/** Test fixture factory for domain objects. */
public final class TestAlerts {

  private TestAlerts() {}

  public static Alert alert(Severity severity) {
    return alert(UUID.randomUUID(), "P-0001", AlertType.THRESHOLD, severity, "HEART_RATE");
  }

  public static Alert alert(
      UUID id, String patientId, AlertType type, Severity severity, String metric) {
    return Alert.open(
        id,
        patientId,
        type,
        severity,
        metric,
        metric == null ? null : 142.0,
        null,
        "heart rate high",
        Instant.parse("2026-11-11T20:15:00Z"),
        Instant.parse("2026-11-11T20:16:00Z"),
        Instant.parse("2026-11-11T20:16:02Z"));
  }
}
