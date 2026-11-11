package io.vitalstream.clinical.infrastructure.sqs;

import io.micrometer.core.instrument.MeterRegistry;
import io.vitalstream.clinical.application.Correlation;
import io.vitalstream.clinical.application.OutboxMessage;
import io.vitalstream.clinical.application.OutboxSink;
import io.vitalstream.clinical.domain.OutboxEventType;
import io.vitalstream.clinical.infrastructure.config.ClinicalProperties;
import java.util.Map;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import tools.jackson.databind.json.JsonMapper;

/** Sends CRITICAL alerts to the {@code alert-escalations} SQS queue for the on-call workflow. */
@Component
public class SqsEscalationSink implements OutboxSink {

  private final SqsClient sqs;
  private final JsonMapper mapper;
  private final String queueName;
  private final MeterRegistry meters;
  private volatile String queueUrl;

  public SqsEscalationSink(
      SqsClient sqs, JsonMapper mapper, ClinicalProperties properties, MeterRegistry meters) {
    this.sqs = sqs;
    this.mapper = mapper;
    this.queueName = properties.sqs().queueName();
    this.meters = meters;
  }

  @Override
  public OutboxEventType handles() {
    return OutboxEventType.ALERT_ESCALATE;
  }

  @Override
  public void deliver(OutboxMessage message) {
    SendMessageRequest.Builder request =
        SendMessageRequest.builder()
            .queueUrl(queueUrl())
            .messageBody(mapper.writeValueAsString(message.alert()));
    if (message.correlationId() != null) {
      request.messageAttributes(
          Map.of(
              Correlation.MESSAGE_HEADER,
              MessageAttributeValue.builder()
                  .dataType("String")
                  .stringValue(message.correlationId())
                  .build()));
    }
    sqs.sendMessage(request.build());
    meters.counter("notifications.sent", "channel", "sqs").increment();
  }

  private String queueUrl() {
    String url = queueUrl;
    if (url == null) {
      url = sqs.getQueueUrl(GetQueueUrlRequest.builder().queueName(queueName).build()).queueUrl();
      queueUrl = url;
    }
    return url;
  }
}
