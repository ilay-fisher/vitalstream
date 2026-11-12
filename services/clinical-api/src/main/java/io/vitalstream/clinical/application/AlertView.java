package io.vitalstream.clinical.application;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import io.vitalstream.clinical.domain.Alert;
import io.vitalstream.clinical.domain.AlertStatus;
import io.vitalstream.clinical.domain.AlertType;
import io.vitalstream.clinical.domain.Severity;
import java.time.Instant;
import java.util.UUID;

/**
 * Read model of an alert <em>episode</em> (CONTRACTS sections 3.4 and 4): the latest detection's
 * fields plus lifecycle state. The same shape is returned by REST, pushed over SSE and Redis
 * pub/sub, queued to SQS and indexed in Elasticsearch, so every consumer sees one representation.
 * The episode fields are additive, old clients keep working. Counts are boxed so that messages from
 * a replica or an index document that predates episodes still parse during a rolling upgrade.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record AlertView(
    @Schema(description = "Episode id (not a detection id)") UUID alertId,
    String patientId,
    AlertType type,
    @Schema(description = "Highest severity seen while the episode was open, never lowered")
        Severity severity,
    String metric,
    @Schema(description = "Latest detection's value") Double value,
    @Schema(description = "Latest detection's score") Double score,
    @Schema(description = "Latest detection's message") String message,
    @Schema(description = "Window start of the latest detection") Instant windowStart,
    @Schema(description = "Window end of the latest detection") Instant windowEnd,
    @Schema(description = "When the latest detection was raised") Instant detectedAt,
    AlertStatus status,
    String acknowledgedBy,
    Instant acknowledgedAt,
    String acknowledgementNote,
    @Schema(description = "Bumps on every episode change; SSE event id is alertId:version")
        long version,
    @Schema(description = "Window start of the episode's first detection") Instant firstSeenAt,
    @Schema(description = "Window end of the latest detection; the resolver watches this")
        Instant lastSeenAt,
    @Schema(description = "Number of detection windows folded into the episode")
        Integer occurrences,
    @Schema(description = "Set when the episode auto-resolved after the silence period, else null")
        Instant resolvedAt,
    @Schema(description = "NEWS2 episode this one is grouped under, null for top-level episodes")
        UUID parentAlertId,
    @Schema(description = "Number of non-resolved child episodes") Integer childCount,
    @Schema(description = "Id of the latest absorbed stream-processor detection")
        UUID lastDetectionId) {

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
        a.version(),
        a.firstSeenAt(),
        a.lastSeenAt(),
        a.occurrences(),
        a.resolvedAt(),
        a.parentAlertId(),
        a.childCount(),
        a.lastDetectionId());
  }
}
