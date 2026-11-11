package io.vitalstream.clinical.infrastructure.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.vitalstream.clinical.infrastructure.kafka.DeadLetterRecoverer;
import io.vitalstream.clinical.infrastructure.kafka.PoisonMessageException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * Error handling for the alert listener: exponential backoff for transient failures, immediate
 * dead-lettering for poison records, offsets committed after recovery (manual ack mode).
 */
@Configuration
public class KafkaConfig {

  @Bean
  DefaultErrorHandler kafkaErrorHandler(
      KafkaOperations<?, ?> template, ClinicalProperties properties, MeterRegistry meters) {
    ClinicalProperties.Kafka kafka = properties.kafka();
    ExponentialBackOff backOff =
        new ExponentialBackOff(kafka.backoffInitial().toMillis(), kafka.backoffMultiplier());
    backOff.setMaxInterval(kafka.backoffMax().toMillis());
    backOff.setMaxAttempts(kafka.retries());
    DefaultErrorHandler handler =
        new DefaultErrorHandler(
            new DeadLetterRecoverer(template, kafka.dlqTopic(), meters), backOff);
    handler.addNotRetryableExceptions(PoisonMessageException.class);
    handler.setCommitRecovered(true);
    return handler;
  }
}
