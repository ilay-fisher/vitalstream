package io.vitalstream.ingest.infrastructure.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Gateway-specific settings (prefix {@code ingest}); connection settings for Kafka and Redis use
 * the standard Spring properties, fed from {@code KAFKA_BOOTSTRAP_SERVERS} and {@code REDIS_URL}.
 *
 * @param topic Kafka topic readings are published to
 * @param serviceName value of the {@code source} Kafka header
 * @param idempotencyTtl how long an event id is remembered
 * @param logPseudonymKey HMAC key for the {@code patientRef} log field; override outside local dev
 */
@ConfigurationProperties("ingest")
public record IngestProperties(
    @DefaultValue("vitals.raw") String topic,
    @DefaultValue("ingest-gateway") String serviceName,
    @DefaultValue("24h") Duration idempotencyTtl,
    @DefaultValue("dev-only-pseudonym-key") String logPseudonymKey,
    @DefaultValue RateLimit rateLimit) {

  /**
   * Per-device token bucket.
   *
   * @param ratePerSecond sustained requests per second
   * @param burst bucket capacity
   */
  public record RateLimit(
      @DefaultValue("50") double ratePerSecond, @DefaultValue("100") int burst) {}
}
