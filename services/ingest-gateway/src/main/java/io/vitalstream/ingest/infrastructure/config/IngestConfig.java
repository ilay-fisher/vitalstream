package io.vitalstream.ingest.infrastructure.config;

import ca.uhn.fhir.context.FhirContext;
import io.micrometer.core.instrument.MeterRegistry;
import io.vitalstream.ingest.adapters.inbound.DeviceBatchAdapter;
import io.vitalstream.ingest.adapters.inbound.FhirAdapter;
import io.vitalstream.ingest.adapters.inbound.Hl7AckBuilder;
import io.vitalstream.ingest.adapters.inbound.Hl7OruAdapter;
import io.vitalstream.ingest.adapters.outbound.KafkaEventPublisher;
import io.vitalstream.ingest.adapters.outbound.RedisIdempotencyStore;
import io.vitalstream.ingest.adapters.outbound.RedisTokenBucketRateLimiter;
import io.vitalstream.ingest.domain.port.EventPublisher;
import io.vitalstream.ingest.domain.port.IdempotencyStore;
import io.vitalstream.ingest.domain.port.IngestMetrics;
import io.vitalstream.ingest.domain.port.RateLimiter;
import io.vitalstream.ingest.domain.service.IngestService;
import io.vitalstream.ingest.domain.service.ReadingValidator;
import io.vitalstream.ingest.infrastructure.logging.PatientPseudonymizer;
import io.vitalstream.ingest.infrastructure.metrics.MicrometerIngestMetrics;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Wires domain, adapters and infrastructure together; the domain itself has no Spring types. */
@Configuration
public class IngestConfig {

  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }

  @Bean
  ReadingValidator readingValidator(Clock clock) {
    return new ReadingValidator(clock);
  }

  @Bean
  IdempotencyStore idempotencyStore(StringRedisTemplate redis, IngestProperties props) {
    return new RedisIdempotencyStore(redis, props.idempotencyTtl());
  }

  @Bean
  RateLimiter rateLimiter(StringRedisTemplate redis, IngestProperties props) {
    return new RedisTokenBucketRateLimiter(
        redis, props.rateLimit().ratePerSecond(), props.rateLimit().burst());
  }

  @Bean
  PatientPseudonymizer patientPseudonymizer(IngestProperties props) {
    return new PatientPseudonymizer(props.logPseudonymKey());
  }

  @Bean
  EventPublisher eventPublisher(
      KafkaTemplate<String, String> kafka,
      JsonMapper json,
      MeterRegistry meters,
      PatientPseudonymizer pseudonymizer,
      IngestProperties props) {
    return new KafkaEventPublisher(
        kafka, json, meters, props.topic(), props.serviceName(), pseudonymizer);
  }

  @Bean
  IngestMetrics ingestMetrics(MeterRegistry registry) {
    return new MicrometerIngestMetrics(registry);
  }

  @Bean
  IngestService ingestService(
      ReadingValidator validator,
      IdempotencyStore idempotency,
      RateLimiter rateLimiter,
      EventPublisher publisher,
      IngestMetrics metrics) {
    return new IngestService(validator, idempotency, rateLimiter, publisher, metrics);
  }

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
