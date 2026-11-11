package io.vitalstream.clinical.infrastructure.config;

import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Declares the notification exchange (idempotent, same shape as the infra definition). Queues and
 * bindings are owned by the consumers and by infra, not by this producer.
 */
@Configuration
public class RabbitConfig {

  @Bean
  TopicExchange alertsNotifyExchange(ClinicalProperties properties) {
    return new TopicExchange(properties.rabbit().exchange(), true, false);
  }
}
