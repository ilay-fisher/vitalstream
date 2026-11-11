package io.vitalstream.clinical.infrastructure.config;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.vitalstream.clinical.application.OutboxRepository;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Cross-cutting beans: clock, scheduling and the outbox lag gauge. */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(ClinicalProperties.class)
public class AppConfig {

  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }

  /** Age of the oldest unpublished outbox row: the single best health signal of the relay. */
  @Bean
  MeterBinder outboxLagGauge(OutboxRepository outbox) {
    return registry ->
        Gauge.builder("outbox_lag_seconds", outbox, OutboxRepository::oldestUnpublishedAgeSeconds)
            .description("Age of the oldest unpublished outbox row")
            .register(registry);
  }
}
