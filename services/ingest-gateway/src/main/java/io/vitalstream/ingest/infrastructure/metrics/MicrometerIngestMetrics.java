package io.vitalstream.ingest.infrastructure.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.vitalstream.ingest.domain.model.RejectionReason;
import io.vitalstream.ingest.domain.model.Source;
import io.vitalstream.ingest.domain.port.IngestMetrics;

/**
 * Micrometer implementation of the business counters. Tag values come from closed enums, so
 * cardinality is bounded; Prometheus exposes them as {@code
 * vitalstream_events_ingested_total{source=batch|fhir|hl7}}, {@code
 * vitalstream_events_rejected_total{reason}} and {@code vitals_duplicates_total}.
 */
public class MicrometerIngestMetrics implements IngestMetrics {

  private final MeterRegistry registry;

  public MicrometerIngestMetrics(MeterRegistry registry) {
    this.registry = registry;
  }

  @Override
  public void ingested(Source source) {
    registry.counter("vitalstream.events.ingested", "source", label(source)).increment();
  }

  @Override
  public void rejected(RejectionReason reason) {
    registry.counter("vitalstream.events.rejected", "reason", reason.name()).increment();
  }

  private static String label(Source source) {
    return switch (source) {
      case DEVICE_BATCH -> "batch";
      case FHIR -> "fhir";
      case HL7V2 -> "hl7";
    };
  }

  @Override
  public void duplicate() {
    registry.counter("vitals.duplicates").increment();
  }
}
