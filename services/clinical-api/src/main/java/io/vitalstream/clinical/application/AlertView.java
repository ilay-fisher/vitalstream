package io.vitalstream.clinical.application;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.vitalstream.clinical.domain.Alert;
import io.vitalstream.clinical.domain.AlertStatus;
import io.vitalstream.clinical.domain.AlertType;
import io.vitalstream.clinical.domain.Severity;
import java.time.Instant;
import java.util.UUID;

/**
 * Read model of an alert: AlertEvent fields plus lifecycle state (CONTRACTS section 4). The same
 * shape is returned by REST, pushed over SSE and Redis pub/sub, queued to SQS and indexed in
 * Elasticsearch, so every consumer sees one representation.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record AlertView(
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
    Instant detectedAt,
    AlertStatus status,
    String acknowledgedBy,
    Instant acknowledgedAt,
    String acknowledgementNote,
    long version) {

  public static AlertView from(Alert a) {
    return new AlertView(
        a.id(),
        a.patientId(),
        a.type(),
        a.severity(),
        a.metric(),
        a.value(),
        a.score(),
        a.message(),
        a.windowStart(),
        a.windowEnd(),
        a.detectedAt(),
        a.status(),
        a.acknowledgedBy(),
        a.acknowledgedAt(),
        a.acknowledgementNote(),
        a.version());
  }
}
