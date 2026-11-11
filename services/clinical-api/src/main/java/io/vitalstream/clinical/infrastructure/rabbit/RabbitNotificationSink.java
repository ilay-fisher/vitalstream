package io.vitalstream.clinical.infrastructure.rabbit;

import io.micrometer.core.instrument.MeterRegistry;
import io.vitalstream.clinical.application.Correlation;
import io.vitalstream.clinical.application.OutboxMessage;
import io.vitalstream.clinical.application.OutboxSink;
import io.vitalstream.clinical.domain.OutboxEventType;
import io.vitalstream.clinical.infrastructure.config.ClinicalProperties;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Publishes alerts to the {@code alerts.notify} topic exchange with routing key {@code
 * alert.<severity>}. Waits for the broker's publisher confirm, and treats an unroutable (returned)
 * message as a failure, so the outbox row is retried instead of the notification silently lost.
 */
@Component
public class RabbitNotificationSink implements OutboxSink {

  private final RabbitTemplate rabbit;
  private final JsonMapper mapper;
  private final ClinicalProperties.Rabbit settings;
  private final MeterRegistry meters;

  public RabbitNotificationSink(
      RabbitTemplate rabbit,
      JsonMapper mapper,
      ClinicalProperties properties,
      MeterRegistry meters) {
    this.rabbit = rabbit;
    this.mapper = mapper;
    this.settings = properties.rabbit();
    this.meters = meters;
  }

  @Override
  public OutboxEventType handles() {
    return OutboxEventType.ALERT_NOTIFY;
  }

  @Override
  public void deliver(OutboxMessage message) throws Exception {
    String routingKey = message.alert().severity().routingKey();
    MessageProperties properties = new MessageProperties();
    properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
    properties.setContentEncoding(StandardCharsets.UTF_8.name());
    properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
    properties.setMessageId(message.alert().alertId().toString());
    if (message.correlationId() != null) {
      properties.setHeader(Correlation.MESSAGE_HEADER, message.correlationId());
    }
    Message amqp =
        MessageBuilder.withBody(mapper.writeValueAsBytes(message.alert()))
            .andProperties(properties)
            .build();

    CorrelationData confirm = new CorrelationData(message.alert().alertId().toString());
    rabbit.send(settings.exchange(), routingKey, amqp, confirm);

    CorrelationData.Confirm result =
        confirm.getFuture().get(settings.confirmTimeout().toMillis(), TimeUnit.MILLISECONDS);
    if (!result.isAck()) {
      throw new IllegalStateException("Broker nacked message: " + result.getReason());
    }
    if (confirm.getReturned() != null) {
      throw new IllegalStateException("No queue bound for routing key " + routingKey);
    }
    meters.counter("notifications.sent", "channel", "rabbitmq").increment();
  }
}
