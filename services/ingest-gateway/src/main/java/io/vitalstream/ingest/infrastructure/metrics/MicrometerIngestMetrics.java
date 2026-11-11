package io.vitalstream.ingest.infrastructure.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.vitalstream.ingest.domain.model.Metric;
import io.vitalstream.ingest.domain.model.RejectionReason;
import io.vitalstream.ingest.domain.model.Source;
import io.vitalstream.ingest.domain.port.IngestMetrics;

/**
 * Micrometer implementation of the business counters. Tag values come from closed enums, so
 * cardinality is bounded; Prometheus exposes them as {@code vitals_ingested_total}, {@code
 * vitals_rejected_total} and {@code vitals_duplicates_total}.
 */
public class MicrometerIngestMetrics implements IngestMetrics {

  private final MeterRegistry registry;

  public MicrometerIngestMetrics(MeterRegistry registry) {
    this.registry = registry;
  }

  @Override
  public void ingested(Source source, Metric metric) {
    registry
        .counter("vitals.ingested", "source", source.name(), "metric", metric.name())
        .increment();
  }

  @Override
  public void rejected(RejectionReason reason) {
    registry.counter("vitals.rejected", "reason", reason.name()).increment();
  }

  @Override
  public void duplicate() {
    registry.counter("vitals.duplicates").increment();
  }
}
