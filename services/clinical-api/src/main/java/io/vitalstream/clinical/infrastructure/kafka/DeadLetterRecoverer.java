package io.vitalstream.clinical.infrastructure.kafka;

import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;

/**
 * Publishes a record that exhausted its retries (or was poison) to the dead-letter topic with the
 * original bytes plus the {@code error} and {@code originalTopic} headers from CONTRACTS section 2.
 */
public class DeadLetterRecoverer implements ConsumerRecordRecoverer {

  private static final Logger log = LoggerFactory.getLogger(DeadLetterRecoverer.class);
  private static final int MAX_ERROR_LENGTH = 500;

  private final DeadLetterPublishingRecoverer delegate;
  private final MeterRegistry meters;
  private final String dlqTopic;

  public DeadLetterRecoverer(
      KafkaOperations<?, ?> template, String dlqTopic, MeterRegistry meters) {
    this.dlqTopic = dlqTopic;
    this.meters = meters;
    this.delegate =
        new DeadLetterPublishingRecoverer(template, (rec, ex) -> new TopicPartition(dlqTopic, -1));
    this.delegate.setHeadersFunction((rec, ex) -> errorHeaders(rec, ex));
  }

  @Override
  public void accept(ConsumerRecord<?, ?> record, Exception exception) {
    delegate.accept(record, exception);
    meters.counter("alerts.dlq").increment();
    log.error(
        "Record {}-{}@{} dead-lettered to {}",
        record.topic(),
        record.partition(),
        record.offset(),
        dlqTopic,
        exception);
  }

  static Headers errorHeaders(ConsumerRecord<?, ?> record, Exception exception) {
    Throwable root = exception;
    while (root.getCause() != null && root.getCause() != root) {
      root = root.getCause();
    }
    String message = root.getClass().getSimpleName() + ": " + root.getMessage();
    if (message.length() > MAX_ERROR_LENGTH) {
      message = message.substring(0, MAX_ERROR_LENGTH);
    }
    Headers headers = new RecordHeaders();
    headers.add("error", message.getBytes(StandardCharsets.UTF_8));
    headers.add("originalTopic", record.topic().getBytes(StandardCharsets.UTF_8));
    return headers;
  }
}
