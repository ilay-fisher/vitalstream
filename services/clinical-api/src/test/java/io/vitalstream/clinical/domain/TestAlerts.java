package io.vitalstream.clinical.domain;

import java.time.Instant;
import java.util.UUID;

/** Test fixture factory for domain objects. */
public final class TestAlerts {

  /** Window start of minute 0; detections of later minutes are one minute apart. */
  public static final Instant T0 = Instant.parse("2026-11-11T20:15:00Z");

  private TestAlerts() {}

  public static Detection detection(AlertType type, Severity severity, String metric, int minute) {
    Instant start = T0.plusSeconds(60L * minute);
    return new Detection(
        UUID.randomUUID(),
        "P-0001",
        type,
        severity,
        metric,
        metric == null ? null : 142.0,
        null,
        "heart rate high",
        start,
        start.plusSeconds(60),
        start.plusSeconds(62));
  }

  public static Detection detection(Severity severity, int minute) {
    return detection(AlertType.THRESHOLD, severity, "HEART_RATE", minute);
  }

  /** A freshly opened THRESHOLD/HEART_RATE episode for P-0001. */
  public static Alert alert(Severity severity) {
    return Alert.open(detection(severity, 0));
  }

  /** A freshly opened episode with a chosen id, patient, type and metric. */
  public static Alert alert(
      UUID id, String patientId, AlertType type, Severity severity, String metric) {
    Detection d = detection(type, severity, metric, 0);
    Alert opened = Alert.open(d);
    return new Alert(
        id,
        patientId,
        type,
        severity,
        metric,
        d.value(),
        d.score(),
        d.message(),
        d.windowStart(),
        d.windowEnd(),
        d.detectedAt(),
        AlertStatus.OPEN,
        null,
        null,
        null,
        0,
        opened.firstSeenAt(),
        opened.lastSeenAt(),
        1,
        null,
        null,
        0,
        d.id(),
        false);
  }
}
