package io.vitalstream.clinical.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Aggregate for a clinical alert. Immutable: state changes return a new instance so that the
 * persisted {@code version} is the only thing that changes under optimistic locking.
 */
public record Alert(
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
    Instant detectedAt,
    AlertStatus status,
    String acknowledgedBy,
    Instant acknowledgedAt,
    String acknowledgementNote,
    long version) {

  public Alert {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(patientId, "patientId");
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(severity, "severity");
    Objects.requireNonNull(status, "status");
  }

  /** Creates a freshly detected alert: status OPEN, version 0. */
  public static Alert open(
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
    return new Alert(
        id,
        patientId,
        type,
        severity,
        metric,
        value,
        score,
        message,
        windowStart,
        windowEnd,
        detectedAt,
        AlertStatus.OPEN,
        null,
        null,
        null,
        0);
  }

  /** Copy carrying the version assigned by the store after a successful optimistic update. */
  public Alert withVersion(long newVersion) {
    return new Alert(
        id,
        patientId,
        type,
        severity,
        metric,
        value,
        score,
        message,
        windowStart,
        windowEnd,
        detectedAt,
        status,
        acknowledgedBy,
        acknowledgedAt,
        acknowledgementNote,
        newVersion);
  }

  /**
   * Records a clinician acknowledgement.
   *
   * @throws InvalidStatusTransitionException if the alert is not OPEN
   */
  public Alert acknowledge(String by, String note, Instant at) {
    if (!status.canTransitionTo(AlertStatus.ACKNOWLEDGED)) {
      throw new InvalidStatusTransitionException(status, AlertStatus.ACKNOWLEDGED);
    }
    return new Alert(
        id,
        patientId,
        type,
        severity,
        metric,
        value,
        score,
        message,
        windowStart,
        windowEnd,
        detectedAt,
        AlertStatus.ACKNOWLEDGED,
        by,
        at,
        note,
        version);
  }
}
