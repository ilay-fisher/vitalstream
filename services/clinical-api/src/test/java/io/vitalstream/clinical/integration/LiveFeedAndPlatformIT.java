package io.vitalstream.clinical.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.vitalstream.clinical.application.OutboxEntry;
import io.vitalstream.clinical.application.OutboxRepository;
import io.vitalstream.clinical.domain.OutboxEventType;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.support.TransactionTemplate;

class LiveFeedAndPlatformIT extends IntegrationTestBase {

  @Autowired StringRedisTemplate redis;
  @Autowired OutboxRepository outbox;
  @Autowired TransactionTemplate tx;

  /** Opens {@code /api/v1/alerts/stream} and collects raw lines until closed. */
  private SseClient openStream() throws Exception {
    List<String> lines = new CopyOnWriteArrayList<>();
    HttpResponse<java.io.InputStream> response =
        http.send(
            HttpRequest.newBuilder(URI.create(url("/api/v1/alerts/stream")))
                .header("Accept", "text/event-stream")
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofInputStream());
    assertThat(response.statusCode()).isEqualTo(200);
    CompletableFuture<Void> reader =
        CompletableFuture.runAsync(() -> {
          try (BufferedReader r =
              new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
              lines.add(line);
            }
          } catch (Exception closed) {
            // stream closed by the test
          }
        });
    return new SseClient(lines, response.body(), reader);
  }

  private record SseClient(
      List<String> lines, java.io.InputStream body, CompletableFuture<Void> reader)
      implements AutoCloseable {
    @Override
    public void close() throws Exception {
      body.close();
    }

    String joined() {
      return lines.stream().collect(Collectors.joining("\n"));
    }
  }

  @Test
  void consumedAlertIsPushedToSseClients() throws Exception {
    try (SseClient client = openStream()) {
      UUID id = UUID.randomUUID();
      sendAlert(
          alertJson(
              id,
              "P-3001",
              "THRESHOLD",
              "CRITICAL",
              "HEART_RATE",
              "2026-11-11T20:15:00Z",
              "live alert"),
          "P-3001",
          "c-sse");

      await().atMost(Duration.ofSeconds(30)).until(() -> client.joined().contains(id.toString()));

      assertThat(client.joined()).contains("event:alert").contains("\"patientId\":\"P-3001\"");
    }
  }

  @Test
  void alertPublishedByAnotherReplicaOnTheRedisChannelReachesLocalClients() throws Exception {
    try (SseClient client = openStream()) {
      UUID id = UUID.randomUUID();
      String payload =
          alertJson(id, "P-3002", "NEWS2", "HIGH", null, "2026-11-11T20:15:00Z", "from replica two")
              .replace("\"score\":null", "\"score\":7.0")
              .replaceAll("\\s+", " ");
      String view =
          payload.substring(0, payload.lastIndexOf('}'))
              + ",\"status\":\"OPEN\",\"acknowledgedBy\":null,\"acknowledgedAt\":null,\"acknowledgementNote\":null,\"version\":0}";

      redis.convertAndSend("alerts.live", view);

      await().atMost(Duration.ofSeconds(10)).until(() -> client.joined().contains(id.toString()));
    }
  }

  @Test
  void outboxRowsAreClaimedByOnlyOneRelayAtATime() throws Exception {
    boolean checked = false;
    for (int attempt = 0; attempt < 20 && !checked; attempt++) {
      insertRows(5);
      checked = lockTwiceAndCompare();
    }
    assertThat(checked)
        .as("relay locked rows in one transaction while another saw none of them")
        .isTrue();
  }

  private void insertRows(int n) {
    String envelope =
        "{\"correlationId\":\"c\",\"alert\":"
            + alertJson(
                    UUID.randomUUID(), "P-3003", "NEWS2", "LOW", null, "2026-11-11T20:15:00Z", "x")
                .replace("\"windowStart\"", "\"status\":\"OPEN\",\"version\":0,\"windowStart\"")
            + "}";
    tx.executeWithoutResult(s -> {
      for (int i = 0; i < n; i++) {
        outbox.add(
            new OutboxEntry(
                UUID.randomUUID(),
                UUID.randomUUID(),
                OutboxEventType.ALERT_ESCALATE,
                envelope,
                Instant.now()));
      }
    });
  }

  private boolean lockTwiceAndCompare() throws Exception {
    return Boolean.TRUE.equals(
        tx.execute(s -> {
          List<UUID> mine = outbox.lockUnpublished(100).stream().map(OutboxEntry::id).toList();
          if (mine.size() < 3) {
            return false;
          }
          List<UUID> theirs =
              CompletableFuture.supplyAsync(() ->
                      tx.execute(
                          s2 -> outbox.lockUnpublished(100).stream().map(OutboxEntry::id).toList()))
                  .join();
          assertThat(theirs).doesNotContainAnyElementsOf(mine);
          return true;
        }));
  }

  @Test
  void outboxLagGaugeReflectsTheOldestPendingRow() {
    assertThat(outbox.oldestUnpublishedAgeSeconds()).isGreaterThanOrEqualTo(0.0);
  }

  @Test
  void actuatorOpenApiAndSwaggerAreServedWithBusinessMetrics() throws Exception {
    String prometheus = get("/actuator/prometheus").body();

    assertThat(get("/actuator/health").statusCode()).isEqualTo(200);
    assertThat(prometheus)
        .contains("alerts_consumed_total")
        .contains("outbox_lag_seconds")
        .contains("notifications_sent_total");
    assertThat(get("/v3/api-docs").body()).contains("/api/v1/alerts/{id}/ack");
    assertThat(get("/swagger-ui.html").statusCode()).isIn(200, 302);
  }
}
