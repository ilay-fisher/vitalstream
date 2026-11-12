package io.vitalstream.clinical.integration;

import static org.awaitility.Awaitility.await;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.GetResponse;
import io.vitalstream.clinical.application.AlertRepository;
import io.vitalstream.clinical.infrastructure.security.TestTokens;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;

/**
 * One Spring context (and therefore one Kafka consumer group member) shared by all integration
 * tests, wired to the singleton containers. Each test uses its own patient id so tests do not see
 * each other's data.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
abstract class IntegrationTestBase {

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", Infra.POSTGRES::getJdbcUrl);
    r.add("spring.datasource.username", Infra.POSTGRES::getUsername);
    r.add("spring.datasource.password", Infra.POSTGRES::getPassword);
    r.add("spring.kafka.bootstrap-servers", Infra.KAFKA::getBootstrapServers);
    r.add(
        "spring.data.redis.url",
        () -> "redis://" + Infra.REDIS.getHost() + ":" + Infra.REDIS.getMappedPort(6379));
    r.add("spring.rabbitmq.addresses", Infra::rabbitUri);
    r.add("vitalstream.elasticsearch.url", Infra::elasticsearchUrl);
    r.add("vitalstream.cassandra.contact-points", Infra::cassandraContactPoint);
    r.add("vitalstream.sqs.endpoint", () -> Infra.LOCALSTACK.getEndpoint().toString());
    r.add("vitalstream.kafka.backoff-initial", () -> "100ms");
    r.add("vitalstream.outbox.interval-ms", () -> "200");
    r.add("vitalstream.dev-tokens-enabled", () -> "true");
    r.add("vitalstream.sse.heartbeat-ms", () -> "1000");
    // test windows are in the past: a running resolver would close every episode within a minute
    r.add("vitalstream.episodes.resolve-interval-ms", () -> "3600000");
  }

  @Value("${local.server.port}")
  protected int port;

  @Autowired protected JdbcClient jdbc;

  @MockitoSpyBean protected AlertRepository alertRepository;

  protected final HttpClient http = HttpClient.newHttpClient();

  protected String url(String path) {
    return "http://localhost:" + port + path;
  }

  /** Token of the default caller: a clinician, so existing tests can read and acknowledge. */
  protected static String clinicianToken() {
    return TestTokens.valid("nurse.levi", "Nurse Levi", "CLINICIAN");
  }

  protected HttpResponse<String> get(String path) throws Exception {
    return get(path, clinicianToken());
  }

  /** GET with the given bearer token, or none at all when {@code token} is null. */
  protected HttpResponse<String> get(String path, String token) throws Exception {
    return http.send(
        authorized(HttpRequest.newBuilder(URI.create(url(path))), token).GET().build(),
        HttpResponse.BodyHandlers.ofString());
  }

  protected HttpResponse<String> postJson(String path, String body) throws Exception {
    return postJson(path, body, clinicianToken());
  }

  protected HttpResponse<String> postJson(String path, String body, String token) throws Exception {
    return http.send(
        authorized(HttpRequest.newBuilder(URI.create(url(path))), token)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  protected static HttpRequest.Builder authorized(HttpRequest.Builder builder, String token) {
    return token == null ? builder : builder.header("Authorization", "Bearer " + token);
  }

  /**
   * AlertEvent JSON (one detection) as the stream processor would produce it: a 1-minute window
   * starting at {@code windowStart}, detected two seconds after it closes.
   */
  protected static String alertJson(
      UUID id,
      String patientId,
      String type,
      String severity,
      String metric,
      String windowStart,
      String message) {
    return """
    {"alertId":"%s","patientId":"%s","type":"%s","severity":"%s","metric":%s,"value":%s,"score":null,
     "message":"%s","windowStart":"%s","windowEnd":"%s","detectedAt":"%s"}
    """
        .formatted(
            id,
            patientId,
            type,
            severity,
            metric == null ? "null" : "\"" + metric + "\"",
            metric == null ? "null" : "142.0",
            message,
            windowStart,
            Instant.parse(windowStart).plusSeconds(60),
            Instant.parse(windowStart).plusSeconds(62));
  }

  protected static void send(String topic, String key, byte[] value, String correlationId) {
    try (KafkaProducer<String, byte[]> producer =
        new KafkaProducer<>(
            Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, Infra.KAFKA.getBootstrapServers()),
            new StringSerializer(),
            new org.apache.kafka.common.serialization.ByteArraySerializer())) {
      ProducerRecord<String, byte[]> record = new ProducerRecord<>(topic, key, value);
      if (correlationId != null) {
        record.headers().add("correlationId", correlationId.getBytes(StandardCharsets.UTF_8));
      }
      producer.send(record);
      producer.flush();
    }
  }

  protected static void sendAlert(String json, String patientId, String correlationId) {
    send("alerts.detected", patientId, json.getBytes(StandardCharsets.UTF_8), correlationId);
  }

  /**
   * Waits until the detection has been folded into an episode and returns the episode id (clients
   * only ever see episode ids; the detection id survives as {@code lastDetectionId}).
   */
  protected UUID awaitEpisode(UUID detectionId) {
    await()
        .atMost(Duration.ofSeconds(30))
        .until(() ->
            jdbc.sql("SELECT count(*) FROM clinical.alerts WHERE last_detection_id = :id")
                    .param("id", detectionId)
                    .query(Long.class)
                    .single()
                == 1L);
    return jdbc.sql("SELECT id FROM clinical.alerts WHERE last_detection_id = :id")
        .param("id", detectionId)
        .query(UUID.class)
        .single();
  }

  protected void awaitOccurrences(UUID episodeId, int occurrences) {
    await()
        .atMost(Duration.ofSeconds(30))
        .until(() ->
            jdbc.sql("SELECT occurrences FROM clinical.alerts WHERE id = :id")
                    .param("id", episodeId)
                    .query(Integer.class)
                    .optional()
                    .orElse(0)
                == occurrences);
  }

  protected long countEpisodes(String patientId) {
    return jdbc.sql("SELECT count(*) FROM clinical.alerts WHERE patient_id = :p")
        .param("p", patientId)
        .query(Long.class)
        .single();
  }

  /** Reads queue {@code notify.all} until a message for the alert shows up (or the wait ends). */
  protected List<GetResponse> rabbitMessagesFor(UUID alertId, Duration wait) throws Exception {
    List<GetResponse> found = new ArrayList<>();
    try (Channel channel = Infra.rabbitChannel()) {
      Instant end = Instant.now().plus(wait);
      while (Instant.now().isBefore(end)) {
        GetResponse response = channel.basicGet("notify.all", true);
        if (response == null) {
          Thread.sleep(100);
        } else if (response.getProps().getMessageId().equals(alertId.toString())) {
          found.add(response);
        }
      }
    }
    return found;
  }

  protected List<Message> sqsMessagesFor(UUID alertId, Duration wait) {
    List<Message> found = new ArrayList<>();
    try (SqsClient sqs = Infra.sqsClient()) {
      String queueUrl = sqs.getQueueUrl(b -> b.queueName("alert-escalations")).queueUrl();
      Instant end = Instant.now().plus(wait);
      while (Instant.now().isBefore(end)) {
        for (Message m :
            sqs.receiveMessage(b ->
                    b.queueUrl(queueUrl)
                        .waitTimeSeconds(1)
                        .maxNumberOfMessages(10)
                        .messageAttributeNames("All"))
                .messages()) {
          if (m.body().contains(alertId.toString())) {
            found.add(m);
          }
        }
      }
    }
    return found;
  }

  protected List<ConsumerRecord<String, byte[]>> dlqRecordsFor(String needle, Duration wait) {
    List<ConsumerRecord<String, byte[]>> found = new ArrayList<>();
    try (KafkaConsumer<String, byte[]> consumer =
        new KafkaConsumer<>(
            Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                Infra.KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG,
                "it-dlq-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
                "earliest"),
            new StringDeserializer(),
            new ByteArrayDeserializer())) {
      consumer.subscribe(List.of("alerts.detected.dlq"));
      Instant end = Instant.now().plus(wait);
      while (found.isEmpty() && Instant.now().isBefore(end)) {
        for (ConsumerRecord<String, byte[]> record : consumer.poll(Duration.ofMillis(500))) {
          if (new String(record.value(), StandardCharsets.UTF_8).contains(needle)) {
            found.add(record);
          }
        }
      }
    }
    return found;
  }

  protected static String header(ConsumerRecord<?, ?> record, String name) {
    var h = record.headers().lastHeader(name);
    return h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
  }
}
