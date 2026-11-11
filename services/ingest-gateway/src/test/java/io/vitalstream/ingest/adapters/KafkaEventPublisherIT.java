package io.vitalstream.ingest.adapters;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vitalstream.ingest.SharedContainers;
import io.vitalstream.ingest.adapters.outbound.KafkaEventPublisher;
import io.vitalstream.ingest.domain.model.Metric;
import io.vitalstream.ingest.domain.model.Source;
import io.vitalstream.ingest.domain.model.VitalReading;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.json.JsonMapper;

@Tag("integration")
class KafkaEventPublisherIT {

  private static final String TOPIC = "vitals.raw";
  private static KafkaEventPublisher publisher;

  @BeforeAll
  static void start() {
    SharedContainers.startKafka();
    var producerFactory =
        new DefaultKafkaProducerFactory<String, String>(
            Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,
                SharedContainers.KAFKA.getBootstrapServers(),
                ProducerConfig.ACKS_CONFIG,
                "all",
                ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG,
                true,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
                StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
                StringSerializer.class));
    publisher =
        new KafkaEventPublisher(
            new KafkaTemplate<>(producerFactory),
            JsonMapper.builder().build(),
            new SimpleMeterRegistry(),
            TOPIC,
            "ingest-gateway",
            new io.vitalstream.ingest.infrastructure.logging.PatientPseudonymizer("k"));
  }

  @Test
  void publishedRecordCanBeConsumedWithKeyHeadersAndPayload() {
    UUID eventId = UUID.randomUUID();
    VitalReading reading =
        new VitalReading(
            eventId,
            "P-0042",
            "DEV-ICU-0001",
            Metric.SPO2,
            97.0,
            "%",
            "59408-5",
            Instant.parse("2026-11-11T20:15:30.123Z"),
            Instant.parse("2026-11-11T20:15:30.456Z"),
            Source.FHIR);
    MDC.put("correlationId", "it-corr-1");
    try {
      publisher.publish(reading).join();
    } finally {
      MDC.clear();
    }

    ConsumerRecord<String, String> record = consumeOne(eventId);

    assertThat(record.key()).isEqualTo("P-0042");
    assertThat(
            new String(
                record.headers().lastHeader("correlationId").value(), StandardCharsets.UTF_8))
        .isEqualTo("it-corr-1");
    assertThat(
            new String(
                record.headers().lastHeader("schemaVersion").value(), StandardCharsets.UTF_8))
        .isEqualTo("1");
    assertThat(new String(record.headers().lastHeader("source").value(), StandardCharsets.UTF_8))
        .isEqualTo(
            "ingest-gateway",
            new io.vitalstream.ingest.infrastructure.logging.PatientPseudonymizer("k"));
    assertThat(record.value()).contains(eventId.toString()).contains("\"metric\":\"SPO2\"");
  }

  private static ConsumerRecord<String, String> consumeOne(UUID eventId) {
    try (var consumer =
        new KafkaConsumer<String, String>(
            Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                SharedContainers.KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG,
                "it-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
                "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class))) {
      consumer.subscribe(List.of(TOPIC));
      long deadline = System.currentTimeMillis() + 20_000;
      while (System.currentTimeMillis() < deadline) {
        for (ConsumerRecord<String, String> r : consumer.poll(Duration.ofMillis(500))) {
          if (r.value().contains(eventId.toString())) {
            return r;
          }
        }
      }
    }
    throw new AssertionError("record " + eventId + " not consumed within 20s");
  }
}
