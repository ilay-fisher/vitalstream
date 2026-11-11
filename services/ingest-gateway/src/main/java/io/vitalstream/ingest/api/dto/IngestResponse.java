package io.vitalstream.ingest.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import io.vitalstream.ingest.domain.service.IngestResult;
import java.util.List;

/**
 * Body of every 202: what happened to each reading in the request. Rejections are reported per
 * reading so a sender can fix and resend only those.
 */
@Schema(description = "Outcome of an ingest request")
public record IngestResponse(int accepted, int duplicates, List<Rejected> rejected) {

  /** A reading that was refused, with a human-readable reason. */
  public record Rejected(
      @Schema(example = "6f1c2a1e-8d0b-4c11-9a77-3f0c2b9e1d10") String eventId,
      @Schema(example = "OUT_OF_RANGE: HEART_RATE 300.0 outside [20.0, 250.0] /min")
          String reason) {}

  public static IngestResponse from(IngestResult result) {
    return new IngestResponse(
        result.accepted(),
        result.duplicates(),
        result.rejected().stream().map(r -> new Rejected(r.eventId(), r.reason())).toList());
  }
}
