package io.vitalstream.ingest.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vitalstream.ingest.domain.model.Metric;
import io.vitalstream.ingest.domain.model.RejectionReason;
import io.vitalstream.ingest.domain.model.Source;
import io.vitalstream.ingest.infrastructure.metrics.MicrometerIngestMetrics;
import org.junit.jupiter.api.Test;

class MicrometerIngestMetricsTest {

  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final MicrometerIngestMetrics metrics = new MicrometerIngestMetrics(registry);

  @Test
  void countsIngestedPerSourceAndMetric() {
    metrics.ingested(Source.FHIR, Metric.SPO2);
    metrics.ingested(Source.FHIR, Metric.SPO2);
    metrics.ingested(Source.HL7V2, Metric.SPO2);

    assertThat(
            registry
                .get("vitals.ingested")
                .tags("source", "FHIR", "metric", "SPO2")
                .counter()
                .count())
        .isEqualTo(2.0);
    assertThat(
            registry
                .get("vitals.ingested")
                .tags("source", "HL7V2", "metric", "SPO2")
                .counter()
                .count())
        .isEqualTo(1.0);
  }

  @Test
  void countsRejectionsByReason() {
    metrics.rejected(RejectionReason.OUT_OF_RANGE);

    assertThat(registry.get("vitals.rejected").tag("reason", "OUT_OF_RANGE").counter().count())
        .isEqualTo(1.0);
  }

  @Test
  void countsDuplicates() {
    metrics.duplicate();
    metrics.duplicate();

    assertThat(registry.get("vitals.duplicates").counter().count()).isEqualTo(2.0);
  }
}
