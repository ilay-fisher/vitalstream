package io.vitalstream.ingest.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Native device batch: one device, one patient, up to 500 readings. Fields inside readings are
 * deliberately loosely typed so a single malformed value rejects only that reading rather than
 * failing deserialisation of the whole request.
 */
@Schema(description = "Batch of readings from a single bedside device")
public record DeviceBatchRequest(
    @Schema(example = "DEV-ICU-0001") @NotBlank String deviceId,
    @Schema(example = "P-0001") @NotBlank String patientId,
    @NotNull @Size(min = 1, max = 500) @Valid List<Reading> readings) {

  /** One measurement; {@code value} accepts any JSON scalar and is checked per reading. */
  public record Reading(
      @Schema(example = "6f1c2a1e-8d0b-4c11-9a77-3f0c2b9e1d10") String eventId,
      @Schema(example = "HEART_RATE") String metric,
      @Schema(type = "number", example = "118.0") Object value,
      @Schema(example = "2026-11-11T20:15:30.123Z") String observedAt) {}
}
