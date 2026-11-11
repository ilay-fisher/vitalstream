package io.vitalstream.ingest.adapters.outbound;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.vitalstream.ingest.domain.model.VitalReading;
import io.vitalstream.ingest.domain.port.EventPublisher;
import io.vitalstream.ingest.infrastructure.logging.PatientPseudonymizer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Publishes readings to {@code vitals.raw}, keyed by patient id so one patient's readings stay
 * ordered within a partition. The returned future completes on the broker acknowledgement ({@code
 * acks=all} on an idempotent producer), which is what lets the HTTP layer promise "accepted" only
 * for data that is durably stored.
 */
public class KafkaEventPublisher implements EventPublisher {

  private static final Logger log = LoggerFactory.getLogger(KafkaEventPublisher.class);

  static final String SCHEMA_VERSION = "1";
  static final String CORRELATION_MDC_KEY = "correlationId";

  private final KafkaTemplate<String, String> kafka;
  private final JsonMapper json;
  private final MeterRegistry meters;
  private final String topic;
  private final String serviceName;
  private final PatientPseudonymizer pseudonymizer;

  public KafkaEventPublisher(
      KafkaTemplate<String, String> kafka,
      JsonMapper json,
      MeterRegistry meters,
      String topic,
      String serviceName,
      PatientPseudonymizer pseudonymizer) {
    this.kafka = kafka;
    this.json = json;
    this.meters = meters;
    this.topic = topic;
    this.serviceName = serviceName;
    this.pseudonymizer = pseudonymizer;
  }

  @Override
  public CompletableFuture<Void> publish(VitalReading reading) {
    String payload;
    try {
      payload = json.writeValueAsString(reading);
    } catch (JacksonException e) {
      return CompletableFuture.failedFuture(e);
    }
    ProducerRecord<String, String> record =
        new ProducerRecord<>(topic, null, reading.patientId(), payload, headers());

    Timer.Sample sample = Timer.start(meters);
    return kafka
        .send(record)
        .handle((result, error) -> {
          sample.stop(timer(error == null ? "success" : "failure"));
          if (error != null) {
            log.error(
                "kafka publish failed eventId={} patientRef={} topic={}",
                reading.eventId(),
                pseudonymizer.ref(reading.patientId()),
                topic,
                error);
            throw new KafkaPublishException(error);
          }
          log.debug(
              "kafka publish acked eventId={} patientRef={} topic={} partition={} offset={}",
              reading.eventId(),
              pseudonymizer.ref(reading.patientId()),
              topic,
              result.getRecordMetadata().partition(),
              result.getRecordMetadata().offset());
          return null;
        });
  }

  private List<Header> headers() {
    String correlationId = MDC.get(CORRELATION_MDC_KEY);
    return List.of(
        header("correlationId", correlationId == null ? "" : correlationId),
        header("schemaVersion", SCHEMA_VERSION),
        header("source", serviceName));
  }

  private static Header header(String key, String value) {
    return new RecordHeader(key, value.getBytes(StandardCharsets.UTF_8));
  }

  private Timer timer(String outcome) {
    return Timer.builder("vitals.kafka.publish")
        .description("Time from send to broker acknowledgement")
        .tag("outcome", outcome)
        .publishPercentileHistogram()
        .register(meters);
  }

  /** Wraps the producer failure so callers see one type regardless of the Kafka client version. */
  static class KafkaPublishException extends RuntimeException {
    KafkaPublishException(Throwable cause) {
      super("Kafka did not acknowledge the record", cause);
    }
  }
}
