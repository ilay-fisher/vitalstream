package io.vitalstream.ingest.domain.service;

import io.vitalstream.ingest.domain.model.Metric;
import io.vitalstream.ingest.domain.model.RawReading;
import io.vitalstream.ingest.domain.model.Rejection;
import io.vitalstream.ingest.domain.model.RejectionReason;
import io.vitalstream.ingest.domain.model.VitalReading;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Applies the CONTRACTS section 3.1 rules to one reading. Validation is per reading, never per
 * request, so one bad value cannot cause a monitor's other readings to be dropped.
 */
public class ReadingValidator {

  /** Contract: observedAt may not be more than five minutes ahead of the gateway clock. */
  static final Duration MAX_FUTURE_SKEW = Duration.ofMinutes(5);

  private static final Pattern PATIENT_ID = Pattern.compile("^P-\\d{4,}$");
  private static final Pattern DEVICE_ID = Pattern.compile("^DEV-[A-Z]+-\\d{4,}$");

  private final Clock clock;

  public ReadingValidator(Clock clock) {
    this.clock = clock;
  }

  /** True if the id has the contract's device-id shape; used to keep junk out of limiter keys. */
  public static boolean isValidDeviceId(String deviceId) {
    return deviceId != null && DEVICE_ID.matcher(deviceId).matches();
  }

  /** Validates and normalises a reading, filling in the catalog unit and LOINC code. */
  public ValidationResult validate(RawReading raw) {
    Instant now = clock.instant();

    Optional<UUID> eventId = parseUuid(raw.eventId());
    if (eventId.isEmpty()) {
      return reject(raw, RejectionReason.INVALID_EVENT_ID, "eventId must be a UUID");
    }
    if (raw.patientId() == null || !PATIENT_ID.matcher(raw.patientId()).matches()) {
      return reject(raw, RejectionReason.INVALID_PATIENT_ID, "expected pattern ^P-\\d{4,}$");
    }
    if (!isValidDeviceId(raw.deviceId())) {
      return reject(
          raw, RejectionReason.INVALID_DEVICE_ID, "expected pattern ^DEV-[A-Z]+-\\d{4,}$");
    }

    Optional<Metric> resolved = resolveMetric(raw);
    if (resolved.isEmpty()) {
      return reject(
          raw, RejectionReason.UNKNOWN_METRIC, "metric=" + raw.metric() + " loinc=" + raw.loinc());
    }
    Metric metric = resolved.get();
    if (raw.loinc() != null && !metric.loinc().equals(raw.loinc())) {
      return reject(
          raw,
          RejectionReason.LOINC_MISMATCH,
          "metric " + metric + " requires LOINC " + metric.loinc() + " but got " + raw.loinc());
    }
    if (raw.unit() != null && !metric.unit().equals(raw.unit())) {
      return reject(
          raw,
          RejectionReason.UNIT_MISMATCH,
          "metric " + metric + " requires unit " + metric.unit() + " but got " + raw.unit());
    }

    if (raw.value() == null) {
      return reject(raw, RejectionReason.MISSING_VALUE, "value is missing or not numeric");
    }
    if (!metric.accepts(raw.value())) {
      return reject(
          raw,
          RejectionReason.OUT_OF_RANGE,
          metric
              + " "
              + raw.value()
              + " outside ["
              + metric.min()
              + ", "
              + metric.max()
              + "] "
              + metric.unit());
    }

    Instant observedAt;
    try {
      observedAt = Instant.parse(raw.observedAt());
    } catch (DateTimeParseException | NullPointerException e) {
      return reject(raw, RejectionReason.INVALID_TIMESTAMP, "observedAt must be ISO-8601 UTC");
    }
    if (observedAt.isAfter(now.plus(MAX_FUTURE_SKEW))) {
      return reject(
          raw, RejectionReason.FUTURE_TIMESTAMP, "observedAt is more than 5 minutes in the future");
    }

    return new ValidationResult.Valid(
        new VitalReading(
            eventId.get(),
            raw.patientId(),
            raw.deviceId(),
            metric,
            raw.value(),
            metric.unit(),
            metric.loinc(),
            observedAt,
            now.truncatedTo(ChronoUnit.MILLIS),
            raw.source()));
  }

  private static Optional<Metric> resolveMetric(RawReading raw) {
    if (raw.metric() != null) {
      return Metric.fromName(raw.metric());
    }
    return Metric.fromLoinc(raw.loinc());
  }

  private static Optional<UUID> parseUuid(String value) {
    if (value == null) {
      return Optional.empty();
    }
    try {
      return Optional.of(UUID.fromString(value));
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
  }

  private static ValidationResult reject(RawReading raw, RejectionReason code, String detail) {
    return new ValidationResult.Invalid(new Rejection(raw.eventId(), code, detail));
  }
}
