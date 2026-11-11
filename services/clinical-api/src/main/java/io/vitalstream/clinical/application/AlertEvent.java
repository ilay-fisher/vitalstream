package io.vitalstream.clinical.application;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.vitalstream.clinical.domain.Alert;
import io.vitalstream.clinical.domain.AlertType;
import io.vitalstream.clinical.domain.Severity;
import java.time.Instant;
import java.util.UUID;

/** Inbound {@code alerts.detected} payload (CONTRACTS section 3.2). */
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

  /** True when the fields every downstream step relies on are present. */
  public boolean isComplete() {
    return alertId != null
        && patientId != null
        && !patientId.isBlank()
        && type != null
        && severity != null
        && detectedAt != null;
  }

  /** Converts the wire event into a new OPEN domain alert. */
  public Alert toAlert() {
    return Alert.open(
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
