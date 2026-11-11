package io.vitalstream.ingest.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * A validated vital sign, identical in shape to the {@code VitalSignEvent} Kafka payload. All three
 * inbound formats are normalised into this one type so downstream code never cares where a reading
 * came from.
 */
public record VitalReading(
    UUID eventId,
    String patientId,
    String deviceId,
    Metric metric,
    double value,
    String unit,
    String loinc,
    Instant observedAt,
    Instant ingestedAt,
    Source source) {}
