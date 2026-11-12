package io.vitalstream.clinical.application;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.vitalstream.clinical.domain.AlertType;
import io.vitalstream.clinical.domain.Detection;
import io.vitalstream.clinical.domain.Severity;
import java.time.Instant;
import java.util.UUID;

/**
 * Inbound {@code alerts.detected} payload (CONTRACTS section 3.2). {@code alertId} is the
 * <em>detection</em> id; clinical-api folds detections into episodes with a different id.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AlertEvent(
    UUID alertId,
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

  /**
   * True when the fields every downstream step relies on are present. The window is required: it
   * orders detections within an episode.
   */
  public boolean isComplete() {
    return alertId != null
        && patientId != null
        && !patientId.isBlank()
        && type != null
        && severity != null
        && windowStart != null
        && windowEnd != null
        && detectedAt != null;
  }

  /** Converts the wire event into a domain detection. */
  public Detection toDetection() {
    return new Detection(
        alertId,
        patientId,
        type,
        severity,
        metric,
        value,
        score,
        message,
        windowStart,
        windowEnd,
        detectedAt);
  }
}
