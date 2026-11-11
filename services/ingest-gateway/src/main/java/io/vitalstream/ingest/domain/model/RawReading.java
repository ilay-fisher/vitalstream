package io.vitalstream.ingest.domain.model;

/**
 * An unvalidated reading as extracted by an inbound adapter. Everything is loosely typed on
 * purpose: adapters only translate structure, the {@code ReadingValidator} owns every rule, so the
 * three formats cannot drift apart.
 *
 * @param eventId idempotency key, expected to be a UUID string
 * @param metric catalog name (batch format); may be null when {@code loinc} identifies it
 * @param loinc LOINC code (FHIR / HL7); may be null when {@code metric} identifies it
 * @param unit UCUM unit as sent; null means "assume the catalog unit"
 * @param value numeric value; null when missing or not numeric
 * @param observedAt ISO-8601 instant string
 */
public record RawReading(
    String eventId,
    String patientId,
    String deviceId,
    String metric,
    String loinc,
    String unit,
    Double value,
    String observedAt,
    Source source) {}
