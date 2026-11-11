package io.vitalstream.ingest.adapters;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vitalstream.ingest.adapters.outbound.KafkaEventPublisher;
import io.vitalstream.ingest.domain.model.Metric;
import io.vitalstream.ingest.domain.model.Source;
import io.vitalstream.ingest.domain.model.VitalReading;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.slf4j.MDC;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import tools.jackson.databind.json.JsonMapper;

class KafkaEventPublisherTest {

  @SuppressWarnings("unchecked")
  private final KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);

  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  private final KafkaEventPublisher publisher =
      new KafkaEventPublisher(
          kafka,
          JsonMapper.builder().build(),
          meters,
          "vitals.raw",
          "ingest-gateway",
          new io.vitalstream.ingest.infrastructure.logging.PatientPseudonymizer("k"));

  private final VitalReading reading =
      new VitalReading(
          UUID.fromString("6f1c2a1e-8d0b-4c11-9a77-3f0c2b9e1d10"),
          "P-0001",
          "DEV-ICU-0001",
          Metric.HEART_RATE,
          118.0,
          "/min",
          "8867-4",
          Instant.parse("2026-11-11T20:15:30.123Z"),
          Instant.parse("2026-11-11T20:15:30.456Z"),
          Source.DEVICE_BATCH);

  @AfterEach
  void clearMdc() {
    MDC.clear();
  }

  @SuppressWarnings("unchecked")
  private ProducerRecord<String, String> sendAndCapture() {
    ArgumentCaptor<ProducerRecord<String, String>> captor =
        ArgumentCaptor.forClass(ProducerRecord.class);
    Mockito.verify(kafka).send(captor.capture());
    return captor.getValue();
  }

  private static String header(ProducerRecord<?, ?> record, String name) {
    return new String(record.headers().lastHeader(name).value(), StandardCharsets.UTF_8);
  }

  @Test
  void sendsContractShapedRecordKeyedByPatient() {
    MDC.put("correlationId", "corr-123");
    when(kafka.send(any(ProducerRecord.class)))
        .thenReturn(CompletableFuture.completedFuture(ackFor()));

    publisher.publish(reading).join();

    ProducerRecord<String, String> record = sendAndCapture();
    assertThat(record.topic()).isEqualTo("vitals.raw");
    assertThat(record.key()).isEqualTo("P-0001");
    assertThat(header(record, "correlationId")).isEqualTo("corr-123");
    assertThat(header(record, "schemaVersion")).isEqualTo("1");
    assertThat(header(record, "source"))
        .isEqualTo(
            "ingest-gateway",
            new io.vitalstream.ingest.infrastructure.logging.PatientPseudonymizer("k"));
    assertThat(record.value())
        .contains("\"eventId\":\"6f1c2a1e-8d0b-4c11-9a77-3f0c2b9e1d10\"")
        .contains("\"metric\":\"HEART_RATE\"")
        .contains("\"value\":118.0")
        .contains("\"observedAt\":\"2026-11-11T20:15:30.123Z\"")
        .contains("\"ingestedAt\":\"2026-11-11T20:15:30.456Z\"")
        .contains("\"source\":\"DEVICE_BATCH\"");
  }

  @Test
  void recordsLatencyTimerOnSuccess() {
    when(kafka.send(any(ProducerRecord.class)))
        .thenReturn(CompletableFuture.completedFuture(ackFor()));

    publisher.publish(reading).join();

    assertThat(meters.get("vitals.kafka.publish").tag("outcome", "success").timer().count())
        .isEqualTo(1);
  }

  @Test
  void failedSendFailsFutureAndRecordsFailureTimer() {
    when(kafka.send(any(ProducerRecord.class)))
        .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")));

    CompletableFuture<Void> result = publisher.publish(reading);

    assertThatThrownBy(result::get).isInstanceOf(ExecutionException.class);
    assertThat(meters.get("vitals.kafka.publish").tag("outcome", "failure").timer().count())
        .isEqualTo(1);
  }

  @Test
  void blankCorrelationIdWhenNoneInMdc() {
    when(kafka.send(any(ProducerRecord.class)))
        .thenReturn(CompletableFuture.completedFuture(ackFor()));

    publisher.publish(reading).join();

    assertThat(header(sendAndCapture(), "correlationId")).isEmpty();
  }

  private SendResult<String, String> ackFor() {
    RecordMetadata metadata =
        new RecordMetadata(new TopicPartition("vitals.raw", 3), 0, 42, 0, 0, 0);
    return new SendResult<>(null, metadata);
  }
}
