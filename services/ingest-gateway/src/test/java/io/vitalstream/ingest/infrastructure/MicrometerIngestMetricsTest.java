package io.vitalstream.ingest.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vitalstream.ingest.domain.model.RejectionReason;
import io.vitalstream.ingest.domain.model.Source;
import io.vitalstream.ingest.infrastructure.metrics.MicrometerIngestMetrics;
import org.junit.jupiter.api.Test;

class MicrometerIngestMetricsTest {

  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final MicrometerIngestMetrics metrics = new MicrometerIngestMetrics(registry);

  @Test
  void countsIngestedPerContractSourceLabel() {
    metrics.ingested(Source.FHIR);
    metrics.ingested(Source.FHIR);
    metrics.ingested(Source.HL7V2);
    metrics.ingested(Source.DEVICE_BATCH);

    assertThat(registry.get("vitalstream.events.ingested").tag("source", "fhir").counter().count())
        .isEqualTo(2.0);
    assertThat(registry.get("vitalstream.events.ingested").tag("source", "hl7").counter().count())
        .isEqualTo(1.0);
    assertThat(registry.get("vitalstream.events.ingested").tag("source", "batch").counter().count())
        .isEqualTo(1.0);
  }

  @Test
  void countsRejectionsByReason() {
    metrics.rejected(RejectionReason.OUT_OF_RANGE);

    assertThat(
            registry
                .get("vitalstream.events.rejected")
                .tag("reason", "OUT_OF_RANGE")
                .counter()
                .count())
        .isEqualTo(1.0);
  }

  @Test
  void countsDuplicates() {
    metrics.duplicate();
    metrics.duplicate();

    assertThat(registry.get("vitals.duplicates").counter().count()).isEqualTo(2.0);
  }
}
