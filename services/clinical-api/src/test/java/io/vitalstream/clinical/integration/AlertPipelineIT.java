package io.vitalstream.clinical.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import io.micrometer.core.instrument.MeterRegistry;
import io.vitalstream.clinical.domain.Alert;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class AlertPipelineIT extends IntegrationTestBase {

  @Autowired MeterRegistry meters;

  @Test
  void criticalAlertIsStoredIndexedNotifiedEscalatedAndCorrelated() throws Exception {
    UUID id = UUID.randomUUID();
    sendAlert(
        alertJson(
            id,
            "P-1001",
            "THRESHOLD",
            "CRITICAL",
            "SPO2",
            "2026-11-11T20:15:00Z",
            "oxygen saturation critically low"),
        "P-1001",
        "corr-pipeline-1");

    awaitAlertStored(id);
    var rabbit = rabbitMessagesFor(id, Duration.ofSeconds(20));
    var sqs = sqsMessagesFor(id, Duration.ofSeconds(5));

    assertThat(rabbit).hasSize(1);
    assertThat(get("/actuator/prometheus").body())
        .containsPattern("vitalstream_alerts_raised_total\\{[^}]*severity=\"CRITICAL\"");
    assertThat(rabbit.getFirst().getEnvelope().getRoutingKey()).isEqualTo("alert.critical");
    assertThat(rabbit.getFirst().getProps().getHeaders().get("correlationId").toString())
        .isEqualTo("corr-pipeline-1");
    assertThat(sqs).hasSize(1);
    assertThat(sqs.getFirst().messageAttributes().get("correlationId").stringValue())
        .isEqualTo("corr-pipeline-1");
    await()
        .atMost(Duration.ofSeconds(20))
        .until(
            () -> get("/api/v1/alerts?q=oxygen&patientId=P-1001").body().contains(id.toString()));
    await()
        .atMost(Duration.ofSeconds(20))
        .until(() ->
            jdbc.sql(
                        "SELECT count(*) FROM clinical.outbox WHERE aggregate_id = :id AND published_at IS NULL")
                    .param("id", id.toString())
                    .query(Long.class)
                    .single()
                == 0L);
    assertThat(
            jdbc.sql("SELECT count(*) FROM clinical.outbox WHERE aggregate_id = :id")
                .param("id", id.toString())
                .query(Long.class)
                .single())
        .isEqualTo(3L);
  }

  @Test
  void replayedAlertIsStoredOnceAndNotifiedOnce() throws Exception {
    UUID id = UUID.randomUUID();
    String json =
        alertJson(
            id,
            "P-1002",
            "THRESHOLD",
            "HIGH",
            "HEART_RATE",
            "2026-11-11T20:15:00Z",
            "heart rate high");
    sendAlert(json, "P-1002", "c-replay");
    sendAlert(json, "P-1002", "c-replay");
    UUID marker = UUID.randomUUID();
    sendAlert(
        alertJson(marker, "P-1002", "NEWS2", "LOW", null, "2026-11-11T20:15:00Z", "marker"),
        "P-1002",
        "c-marker");

    awaitAlertStored(marker);
    var notifications = rabbitMessagesFor(id, Duration.ofSeconds(10));

    assertThat(
            jdbc.sql("SELECT count(*) FROM clinical.alerts WHERE id = :id")
                .param("id", id)
                .query(Long.class)
                .single())
        .isEqualTo(1L);
    assertThat(notifications).hasSize(1);
    assertThat(meters.get("alerts.consumed").tag("outcome", "duplicate").counter().count())
        .isGreaterThanOrEqualTo(1.0);
  }

  @Test
  void repeatedAlertWithinWindowIsStoredAndIndexedButNotNotified() throws Exception {
    UUID first = UUID.randomUUID();
    UUID second = UUID.randomUUID();
    sendAlert(
        alertJson(
            first,
            "P-1003",
            "THRESHOLD",
            "MEDIUM",
            "HEART_RATE",
            "2026-11-11T20:15:00Z",
            "tachycardia sustained"),
        "P-1003",
        "c1");
    awaitAlertStored(first);
    sendAlert(
        alertJson(
            second,
            "P-1003",
            "THRESHOLD",
            "MEDIUM",
            "HEART_RATE",
            "2026-11-11T20:16:00Z",
            "tachycardia sustained"),
        "P-1003",
        "c2");
    awaitAlertStored(second);

    assertThat(rabbitMessagesFor(first, Duration.ofSeconds(10))).hasSize(1);
    assertThat(rabbitMessagesFor(second, Duration.ofSeconds(5))).isEmpty();
    await()
        .atMost(Duration.ofSeconds(20))
        .until(() -> {
          String body = get("/api/v1/alerts?q=tachycardia&patientId=P-1003").body();
          return body.contains(first.toString()) && body.contains(second.toString());
        });
    assertThat(
            jdbc.sql("SELECT count(*) FROM clinical.outbox WHERE aggregate_id = :id")
                .param("id", second.toString())
                .query(Long.class)
                .single())
        .isEqualTo(1L);
  }

  @Test
  void poisonJsonGoesStraightToDeadLetterTopicWithOriginalBytesAndErrorHeaders() {
    String poison = "{this is not json " + UUID.randomUUID();
    send("alerts.detected", "P-1004", poison.getBytes(StandardCharsets.UTF_8), "c-poison");

    var dlq = dlqRecordsFor(poison, Duration.ofSeconds(30));

    assertThat(dlq).hasSize(1);
    assertThat(new String(dlq.getFirst().value(), StandardCharsets.UTF_8)).isEqualTo(poison);
    assertThat(header(dlq.getFirst(), "originalTopic")).isEqualTo("alerts.detected");
    assertThat(header(dlq.getFirst(), "error")).contains("PoisonMessageException");
    assertThat(header(dlq.getFirst(), "correlationId")).isEqualTo("c-poison");
    assertThat(meters.get("alerts.dlq").counter().count()).isGreaterThanOrEqualTo(1.0);
  }

  @Test
  void transientFailuresAreRetriedThenDeadLettered() {
    doThrow(new IllegalStateException("database unavailable"))
        .when(alertRepository)
        .insertIfAbsent(argThat((Alert a) -> "P-FAIL".equals(a.patientId())));
    UUID id = UUID.randomUUID();
    sendAlert(
        alertJson(
            id, "P-FAIL", "NEWS2", "HIGH", null, "2026-11-11T20:15:00Z", "will never persist"),
        "P-FAIL",
        "c-fail");

    var dlq = dlqRecordsFor(id.toString(), Duration.ofSeconds(45));

    assertThat(dlq).hasSize(1);
    assertThat(header(dlq.getFirst(), "error")).contains("database unavailable");
    verify(alertRepository, atLeast(4))
        .insertIfAbsent(argThat((Alert a) -> "P-FAIL".equals(a.patientId())));
  }
}
