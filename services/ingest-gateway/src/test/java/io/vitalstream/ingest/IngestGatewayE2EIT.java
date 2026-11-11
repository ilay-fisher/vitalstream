package io.vitalstream.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Full stack: real HTTP, real Redis, real Kafka. Posts through each endpoint and consumes the
 * records back from {@code vitals.raw}, the same way stream-processor will.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
class IngestGatewayE2EIT {

  private static final DateTimeFormatter HL7_TS =
      DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC);

  private static final int PORT = freePort();

  private static int freePort() {
    try (var socket = new java.net.ServerSocket(0)) {
      return socket.getLocalPort();
    } catch (java.io.IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
  }

  @DynamicPropertySource
  static void containers(DynamicPropertyRegistry registry) {
    SharedContainers.startRedis();
    SharedContainers.startKafka();
    registry.add("spring.data.redis.url", SharedContainers::redisUrl);
    registry.add("spring.kafka.bootstrap-servers", SharedContainers.KAFKA::getBootstrapServers);
    registry.add("server.port", () -> PORT);
    registry.add("ingest.allowed-hosts", () -> "localhost:" + PORT);
    registry.add("ingest.rate-limit.burst", () -> "5");
    registry.add("ingest.rate-limit.rate-per-second", () -> "1");
  }

  private final int port = PORT;

  private final HttpClient http = HttpClient.newHttpClient();
  private final JsonMapper json = JsonMapper.builder().build();

  private static String device() {
    return "DEV-E2E-" + (1000 + (int) (Math.random() * 8999));
  }

  private HttpResponse<String> post(String path, String contentType, String body, String... headers)
      throws Exception {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .header("Content-Type", contentType)
            .header("X-Api-Key", "dev-ingest-key")
            .POST(HttpRequest.BodyPublishers.ofString(body));
    for (int i = 0; i < headers.length; i += 2) {
      request.header(headers[i], headers[i + 1]);
    }
    return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> get(String path) throws Exception {
    return http.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private String batchBody(String device, String id1, String id2, String badId) {
    String now = Instant.now().toString();
    return """
    {"deviceId":"%s","patientId":"P-0001","readings":[
      {"eventId":"%s","metric":"HEART_RATE","value":118.0,"observedAt":"%s"},
      {"eventId":"%s","metric":"SPO2","value":97,"observedAt":"%s"},
      {"eventId":"%s","metric":"HEART_RATE","value":300,"observedAt":"%s"}]}
    """
        .formatted(device, id1, now, id2, now, badId, now);
  }

  @Test
  void batchIsPublishedToKafkaWithHeadersAndDuplicatesAreSkipped() throws Exception {
    String device = device();
    String id1 = UUID.randomUUID().toString();
    String id2 = UUID.randomUUID().toString();
    String bad = UUID.randomUUID().toString();

    HttpResponse<String> first =
        post(
            "/api/v1/vitals/batch",
            "application/json",
            batchBody(device, id1, id2, bad),
            "X-Correlation-Id",
            "e2e-corr-1");

    assertThat(first.statusCode()).isEqualTo(202);
    assertThat(first.headers().firstValue("X-Correlation-Id")).contains("e2e-corr-1");
    JsonNode body = json.readTree(first.body());
    assertThat(body.get("accepted").asInt()).isEqualTo(2);
    assertThat(body.get("duplicates").asInt()).isZero();
    assertThat(body.get("rejected")).hasSize(1);
    assertThat(body.get("rejected").get(0).get("eventId").asString()).isEqualTo(bad);
    assertThat(body.get("rejected").get(0).get("reason").asString()).startsWith("OUT_OF_RANGE");

    List<ConsumerRecord<String, String>> records = consume(Set.of(id1, id2));
    assertThat(records).hasSize(2);
    for (ConsumerRecord<String, String> record : records) {
      assertThat(record.key()).isEqualTo("P-0001");
      assertThat(header(record, "correlationId")).isEqualTo("e2e-corr-1");
      assertThat(header(record, "schemaVersion")).isEqualTo("1");
      assertThat(header(record, "source")).isEqualTo("ingest-gateway");
      JsonNode event = json.readTree(record.value());
      assertThat(event.get("source").asString()).isEqualTo("DEVICE_BATCH");
      assertThat(event.get("deviceId").asString()).isEqualTo(device);
      assertThat(event.has("ingestedAt")).isTrue();
    }

    HttpResponse<String> retry =
        post("/api/v1/vitals/batch", "application/json", batchBody(device, id1, id2, bad));
    JsonNode retryBody = json.readTree(retry.body());
    assertThat(retry.statusCode()).isEqualTo(202);
    assertThat(retryBody.get("accepted").asInt()).isZero();
    assertThat(retryBody.get("duplicates").asInt()).isEqualTo(2);
  }

  @Test
  void fhirObservationIsPublishedAsFhirSource() throws Exception {
    String id = UUID.randomUUID().toString();
    String fhir =
        Fixtures.read("fhir-observation-hr.json")
            .replace("6f1c2a1e-8d0b-4c11-9a77-3f0c2b9e1d10", id)
            .replace("DEV-ICU-0001", device())
            .replace("2026-11-11T20:15:30.123Z", Instant.now().toString());

    HttpResponse<String> response = post("/api/v1/fhir/Observation", "application/fhir+json", fhir);

    assertThat(response.statusCode()).isEqualTo(202);
    assertThat(json.readTree(response.body()).get("accepted").asInt()).isEqualTo(1);
    ConsumerRecord<String, String> record = consume(Set.of(id)).get(0);
    JsonNode event = json.readTree(record.value());
    assertThat(event.get("source").asString()).isEqualTo("FHIR");
    assertThat(event.get("metric").asString()).isEqualTo("HEART_RATE");
    assertThat(event.get("loinc").asString()).isEqualTo("8867-4");
  }

  @Test
  void hl7MessageIsAckedAndPublished() throws Exception {
    String now = HL7_TS.format(Instant.now());
    String hl7 =
        Fixtures.read("hl7-oru-r01.hl7")
            .replace("20261111201530.123+0000", now)
            .replace("20261111201530+0000", now)
            .replace("MSG-0001", "MSG-" + UUID.randomUUID())
            .replace("DEV-ICU-0001", device());

    HttpResponse<String> response = post("/api/v1/hl7", "text/plain", hl7);

    assertThat(response.statusCode()).isEqualTo(202);
    assertThat(response.body()).contains("MSA|AA|MSG-");
  }

  @Test
  void hl7WithOutOfRangeValueIsAckedWithAe() throws Exception {
    String now = HL7_TS.format(Instant.now());
    String hl7 =
        Fixtures.read("hl7-oru-r01.hl7")
            .replace("20261111201530.123+0000", now)
            .replace("20261111201530+0000", now)
            .replace("|118|", "|999|")
            .replace("MSG-0001", "MSG-" + UUID.randomUUID())
            .replace("DEV-ICU-0001", device());

    HttpResponse<String> response = post("/api/v1/hl7", "text/plain", hl7);

    assertThat(response.statusCode()).isEqualTo(202);
    assertThat(response.body()).contains("MSA|AE|").contains("OUT_OF_RANGE");
  }

  @Test
  void deviceOverItsBurstGets429WithRetryAfter() throws Exception {
    String device = device();
    List<Integer> statuses = new ArrayList<>();
    HttpResponse<String> last = null;
    for (int i = 0; i < 7; i++) {
      last =
          post(
              "/api/v1/vitals/batch",
              "application/json",
              batchBody(
                  device,
                  UUID.randomUUID().toString(),
                  UUID.randomUUID().toString(),
                  UUID.randomUUID().toString()));
      statuses.add(last.statusCode());
    }

    assertThat(statuses.subList(0, 5)).containsOnly(202);
    assertThat(statuses.get(6)).isEqualTo(429);
    assertThat(last.headers().firstValue("Retry-After")).isPresent();
    assertThat(last.headers().firstValue("Content-Type").orElse(""))
        .startsWith("application/problem+json");
  }

  @Test
  void requestsWithoutApiKeyAreRejectedButHealthStaysOpen() throws Exception {
    HttpResponse<String> response =
        http.send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/hl7"))
                .header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString("x"))
                .build(),
            HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(401);
    assertThat(get("/actuator/health").statusCode()).isEqualTo(200);
  }

  @Test
  void foreignHostHeaderIsRejected() throws Exception {
    var socket = new java.net.Socket("localhost", port);
    socket
        .getOutputStream()
        .write(
            "GET /actuator/health HTTP/1.1\r\nHost: evil.example.com\r\nConnection: close\r\n\r\n"
                .getBytes(StandardCharsets.UTF_8));
    String reply = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    socket.close();

    assertThat(reply).startsWith("HTTP/1.1 400").contains("invalid-host");
  }

  @Test
  void operationalEndpointsAreExposed() throws Exception {
    assertThat(get("/actuator/health").statusCode()).isEqualTo(200);
    assertThat(get("/v3/api-docs").body()).contains("/api/v1/vitals/batch", "/api/v1/hl7");
    assertThat(get("/swagger-ui/index.html").statusCode()).isEqualTo(200);

    String prometheus = get("/actuator/prometheus").body();
    assertThat(prometheus)
        .contains("vitals_ingested_total")
        .contains("vitals_kafka_publish_seconds");
  }

  private static String header(ConsumerRecord<?, ?> record, String name) {
    return new String(record.headers().lastHeader(name).value(), StandardCharsets.UTF_8);
  }

  private static List<ConsumerRecord<String, String>> consume(Set<String> eventIds) {
    List<ConsumerRecord<String, String>> found = new ArrayList<>();
    try (var consumer =
        new KafkaConsumer<String, String>(
            Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                SharedContainers.KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG,
                "e2e-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
                "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class))) {
      consumer.subscribe(List.of("vitals.raw"));
      long deadline = System.currentTimeMillis() + 20_000;
      while (found.size() < eventIds.size() && System.currentTimeMillis() < deadline) {
        for (ConsumerRecord<String, String> r : consumer.poll(Duration.ofMillis(500))) {
          if (eventIds.stream().anyMatch(r.value()::contains)) {
            found.add(r);
          }
        }
      }
    }
    return found;
  }
}
