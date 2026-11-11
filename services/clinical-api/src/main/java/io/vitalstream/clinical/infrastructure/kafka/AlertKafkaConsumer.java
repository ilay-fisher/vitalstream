package io.vitalstream.clinical.infrastructure.kafka;

import io.vitalstream.clinical.application.AlertEvent;
import io.vitalstream.clinical.application.AlertIngestService;
import io.vitalstream.clinical.application.Correlation;
import io.vitalstream.clinical.application.PatientRef;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code alerts.detected}. The offset is committed manually only after the alert and its
 * outbox rows are durable, so a crash re-delivers rather than loses; re-delivery is harmless
 * because ingestion is idempotent on alertId. Failures propagate to the container's error handler
 * (retry with backoff, then dead-letter).
 */
@Component
public class AlertKafkaConsumer {

  private final AlertEventParser parser;
  private final AlertIngestService ingest;

  public AlertKafkaConsumer(AlertEventParser parser, AlertIngestService ingest) {
    this.parser = parser;
    this.ingest = ingest;
  }

  @KafkaListener(
      id = "alert-consumer",
      topics = "${vitalstream.kafka.alerts-topic:alerts.detected}",
      concurrency = "3")
  public void onAlert(ConsumerRecord<String, byte[]> record, Acknowledgment ack) {
    MDC.put(Correlation.MDC_KEY, correlationIdOf(record));
    MDC.put("topic", record.topic());
    MDC.put("partition", String.valueOf(record.partition()));
    MDC.put("offset", String.valueOf(record.offset()));
    try {
      AlertEvent event = parser.parse(record.value());
      MDC.put("patientRef", PatientRef.of(event.patientId()));
      MDC.put("alertId", event.alertId().toString());
      ingest.ingest(event, MDC.get(Correlation.MDC_KEY));
      ack.acknowledge();
    } finally {
      MDC.clear();
    }
  }

  private static String correlationIdOf(ConsumerRecord<String, byte[]> record) {
    Header header = record.headers().lastHeader(Correlation.MESSAGE_HEADER);
    if (header == null || header.value() == null || header.value().length == 0) {
      return UUID.randomUUID().toString();
    }
    return new String(header.value(), StandardCharsets.UTF_8);
  }
}
