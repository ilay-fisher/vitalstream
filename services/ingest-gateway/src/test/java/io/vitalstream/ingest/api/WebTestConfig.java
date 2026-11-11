package io.vitalstream.ingest.api;

import ca.uhn.fhir.context.FhirContext;
import io.vitalstream.ingest.adapters.inbound.DeviceBatchAdapter;
import io.vitalstream.ingest.adapters.inbound.FhirAdapter;
import io.vitalstream.ingest.adapters.inbound.Hl7AckBuilder;
import io.vitalstream.ingest.adapters.inbound.Hl7OruAdapter;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/** Real (stateless) adapters for controller slice tests; only {@code IngestService} is mocked. */
@TestConfiguration
class WebTestConfig {

  @Bean
  DeviceBatchAdapter deviceBatchAdapter() {
    return new DeviceBatchAdapter();
  }

  @Bean
  FhirAdapter fhirAdapter() {
    return new FhirAdapter(FhirContext.forR4Cached());
  }

  @Bean
  Hl7OruAdapter hl7OruAdapter() {
    return new Hl7OruAdapter();
  }

  @Bean
  Hl7AckBuilder hl7AckBuilder() {
    return new Hl7AckBuilder();
  }
}
