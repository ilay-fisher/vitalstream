package io.vitalstream.ingest.domain;

import static org.assertj.core.api.Assertions.assertThat;

import io.vitalstream.ingest.domain.model.Metric;
import io.vitalstream.ingest.domain.model.RawReading;
import io.vitalstream.ingest.domain.model.RejectionReason;
import io.vitalstream.ingest.domain.model.Source;
import io.vitalstream.ingest.domain.model.VitalReading;
import io.vitalstream.ingest.domain.service.ReadingValidator;
import io.vitalstream.ingest.domain.service.ValidationResult;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class ReadingValidatorTest {

  private static final Instant NOW = Instant.parse("2026-11-11T20:15:31.456789Z");
  private static final String EVENT_ID = "6f1c2a1e-8d0b-4c11-9a77-3f0c2b9e1d10";

  private final ReadingValidator validator = new ReadingValidator(Clock.fixed(NOW, ZoneOffset.UTC));

  private RawReading valid() {
    return new RawReading(
        EVENT_ID,
        "P-0001",
        "DEV-ICU-0001",
        "HEART_RATE",
        null,
        null,
        118.0,
        "2026-11-11T20:15:30.123Z",
        Source.DEVICE_BATCH);
  }

  private static RejectionReason reasonOf(ValidationResult result) {
    assertThat(result).isInstanceOf(ValidationResult.Invalid.class);
    return ((ValidationResult.Invalid) result).rejection().code();
  }

  @Test
  void acceptsValidReadingAndFillsCatalogUnitAndLoinc() {
    ValidationResult result = validator.validate(valid());

    assertThat(result).isInstanceOf(ValidationResult.Valid.class);
    VitalReading reading = ((ValidationResult.Valid) result).reading();
    assertThat(reading.metric()).isEqualTo(Metric.HEART_RATE);
    assertThat(reading.unit()).isEqualTo("/min");
    assertThat(reading.loinc()).isEqualTo("8867-4");
    assertThat(reading.value()).isEqualTo(118.0);
    assertThat(reading.observedAt()).isEqualTo(Instant.parse("2026-11-11T20:15:30.123Z"));
    assertThat(reading.ingestedAt()).isEqualTo(Instant.parse("2026-11-11T20:15:31.456Z"));
    assertThat(reading.source()).isEqualTo(Source.DEVICE_BATCH);
  }

  @Test
  void resolvesMetricFromLoincWhenNameAbsent() {
    RawReading raw =
        new RawReading(
            EVENT_ID,
            "P-0001",
            "DEV-ICU-0001",
            null,
            "59408-5",
            "%",
            97.0,
            "2026-11-11T20:15:30Z",
            Source.FHIR);

    ValidationResult result = validator.validate(raw);

    assertThat(((ValidationResult.Valid) result).reading().metric()).isEqualTo(Metric.SPO2);
  }

  @Test
  void rejectsNonUuidEventId() {
    RawReading raw =
        new RawReading(
            "nope",
            "P-0001",
            "DEV-ICU-0001",
            "HEART_RATE",
            null,
            null,
            80.0,
            "2026-11-11T20:15:30Z",
            Source.DEVICE_BATCH);

    assertThat(reasonOf(validator.validate(raw))).isEqualTo(RejectionReason.INVALID_EVENT_ID);
  }

  @Test
  void rejectsBadPatientAndDeviceIds() {
    RawReading badPatient =
        new RawReading(
            EVENT_ID,
            "X-1",
            "DEV-ICU-0001",
            "HEART_RATE",
            null,
            null,
            80.0,
            "2026-11-11T20:15:30Z",
            Source.DEVICE_BATCH);
    RawReading badDevice =
        new RawReading(
            EVENT_ID,
            "P-0001",
            "dev-1",
            "HEART_RATE",
            null,
            null,
            80.0,
            "2026-11-11T20:15:30Z",
            Source.DEVICE_BATCH);

    assertThat(reasonOf(validator.validate(badPatient)))
        .isEqualTo(RejectionReason.INVALID_PATIENT_ID);
    assertThat(reasonOf(validator.validate(badDevice)))
        .isEqualTo(RejectionReason.INVALID_DEVICE_ID);
  }

  @Test
  void rejectsUnknownMetricAndLoincMismatchAndUnitMismatch() {
    RawReading unknown =
        new RawReading(
            EVENT_ID,
            "P-0001",
            "DEV-ICU-0001",
            "GLUCOSE",
            null,
            null,
            5.0,
            "2026-11-11T20:15:30Z",
            Source.DEVICE_BATCH);
    RawReading loincMismatch =
        new RawReading(
            EVENT_ID,
            "P-0001",
            "DEV-ICU-0001",
            "HEART_RATE",
            "59408-5",
            null,
            80.0,
            "2026-11-11T20:15:30Z",
            Source.DEVICE_BATCH);
    RawReading unitMismatch =
        new RawReading(
            EVENT_ID,
            "P-0001",
            "DEV-ICU-0001",
            "TEMPERATURE",
            null,
            "degF",
            98.6,
            "2026-11-11T20:15:30Z",
            Source.DEVICE_BATCH);

    assertThat(reasonOf(validator.validate(unknown))).isEqualTo(RejectionReason.UNKNOWN_METRIC);
    assertThat(reasonOf(validator.validate(loincMismatch)))
        .isEqualTo(RejectionReason.LOINC_MISMATCH);
    assertThat(reasonOf(validator.validate(unitMismatch))).isEqualTo(RejectionReason.UNIT_MISMATCH);
  }

  @Test
  void rejectsMissingAndOutOfRangeValues() {
    RawReading missing =
        new RawReading(
            EVENT_ID,
            "P-0001",
            "DEV-ICU-0001",
            "HEART_RATE",
            null,
            null,
            null,
            "2026-11-11T20:15:30Z",
            Source.DEVICE_BATCH);
    RawReading high =
        new RawReading(
            EVENT_ID,
            "P-0001",
            "DEV-ICU-0001",
            "HEART_RATE",
            null,
            null,
            251.0,
            "2026-11-11T20:15:30Z",
            Source.DEVICE_BATCH);

    assertThat(reasonOf(validator.validate(missing))).isEqualTo(RejectionReason.MISSING_VALUE);
    ValidationResult outOfRange = validator.validate(high);
    assertThat(reasonOf(outOfRange)).isEqualTo(RejectionReason.OUT_OF_RANGE);
    assertThat(((ValidationResult.Invalid) outOfRange).rejection().reason())
        .startsWith("OUT_OF_RANGE: ")
        .contains("251");
  }

  @Test
  void rejectsBadAndFutureTimestamps() {
    RawReading garbage =
        new RawReading(
            EVENT_ID,
            "P-0001",
            "DEV-ICU-0001",
            "HEART_RATE",
            null,
            null,
            80.0,
            "yesterday",
            Source.DEVICE_BATCH);
    RawReading future =
        new RawReading(
            EVENT_ID,
            "P-0001",
            "DEV-ICU-0001",
            "HEART_RATE",
            null,
            null,
            80.0,
            "2026-11-11T20:20:32Z",
            Source.DEVICE_BATCH);
    RawReading nearFuture =
        new RawReading(
            EVENT_ID,
            "P-0001",
            "DEV-ICU-0001",
            "HEART_RATE",
            null,
            null,
            80.0,
            "2026-11-11T20:20:30Z",
            Source.DEVICE_BATCH);

    assertThat(reasonOf(validator.validate(garbage))).isEqualTo(RejectionReason.INVALID_TIMESTAMP);
    assertThat(reasonOf(validator.validate(future))).isEqualTo(RejectionReason.FUTURE_TIMESTAMP);
    assertThat(validator.validate(nearFuture)).isInstanceOf(ValidationResult.Valid.class);
  }

  @Test
  void rejectionReasonWithoutDetailIsJustTheCode() {
    var rejection =
        new io.vitalstream.ingest.domain.model.Rejection(
            "x", RejectionReason.UNSUPPORTED_RESOURCE, null);

    assertThat(rejection.reason()).isEqualTo("UNSUPPORTED_RESOURCE");
  }
}
