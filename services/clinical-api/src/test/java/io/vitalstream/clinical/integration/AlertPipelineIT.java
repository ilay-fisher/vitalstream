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
import tools.jackson.databind.json.JsonMapper;

/** Kafka to Postgres to RabbitMQ / SQS / Elasticsearch for alert episodes. */
class AlertPipelineIT extends IntegrationTestBase {

  @Autowired MeterRegistry meters;

  private final JsonMapper json = JsonMapper.builder().build();

  private void awaitOutboxDrained(UUID episodeId) {
    await()
        .atMost(Duration.ofSeconds(20))
        .until(() ->
            jdbc.sql(
                        "SELECT count(*) FROM clinical.outbox WHERE aggregate_id = :id AND published_at IS NULL")
                    .param("id", episodeId.toString())
                    .query(Long.class)
                    .single()
                == 0L);
  }

  private long outboxRows(UUID episodeId) {
    return jdbc.sql("SELECT count(*) FROM clinical.outbox WHERE aggregate_id = :id")
        .param("id", episodeId.toString())
        .query(Long.class)
        .single();
  }

  @Test
  void criticalEpisodeIsStoredIndexedNotifiedEscalatedAndCorrelated() throws Exception {
    UUID detection = UUID.randomUUID();
    sendAlert(
        alertJson(
            detection,
            "P-1001",
            "THRESHOLD",
            "CRITICAL",
            "SPO2",
            "2026-11-11T20:15:00Z",
            "oxygen saturation critically low"),
        "P-1001",
        "corr-pipeline-1");

    UUID episode = awaitEpisode(detection);
    var rabbit = rabbitMessagesFor(episode, Duration.ofSeconds(20));
    var sqs = sqsMessagesFor(episode, Duration.ofSeconds(5));

    assertThat(rabbit).hasSize(1);
    assertThat(get("/actuator/prometheus").body())
        .containsPattern("vitalstream_alerts_raised_total\\{[^}]*severity=\"CRITICAL\"")
        .containsPattern("vitalstream_alert_episodes_total\\{[^}]*event=\"opened\"");
    assertThat(rabbit.getFirst().getEnvelope().getRoutingKey()).isEqualTo("alert.critical");
    assertThat(rabbit.getFirst().getProps().getHeaders().get("correlationId").toString())
        .isEqualTo("corr-pipeline-1");
    assertThat(sqs).hasSize(1);
    assertThat(sqs.getFirst().messageAttributes().get("correlationId").stringValue())
        .isEqualTo("corr-pipeline-1");
    await()
        .atMost(Duration.ofSeconds(20))
        .until(() ->
            get("/api/v1/alerts?q=oxygen&patientId=P-1001").body().contains(episode.toString()));
    awaitOutboxDrained(episode);
    assertThat(outboxRows(episode)).isEqualTo(3L);
  }

  @Test
  void replayedDetectionIsStoredOnceAndNotifiedOnce() throws Exception {
    UUID detection = UUID.randomUUID();
    String message =
        alertJson(
            detection,
            "P-1002",
            "THRESHOLD",
            "HIGH",
            "HEART_RATE",
            "2026-11-11T20:15:00Z",
            "heart rate high");
    sendAlert(message, "P-1002", "c-replay");
    sendAlert(message, "P-1002", "c-replay");
    UUID marker = UUID.randomUUID();
    sendAlert(
        alertJson(marker, "P-1002", "NEWS2", "LOW", null, "2026-11-11T20:15:00Z", "marker"),
        "P-1002",
        "c-marker");

    awaitEpisode(marker);
    UUID episode = awaitEpisode(detection);
    var notifications = rabbitMessagesFor(episode, Duration.ofSeconds(10));

    assertThat(countEpisodes("P-1002")).isEqualTo(2L);
    assertThat(
            jdbc.sql("SELECT occurrences FROM clinical.alerts WHERE id = :id")
                .param("id", episode)
                .query(Integer.class)
                .single())
        .isEqualTo(1);
    assertThat(notifications).hasSize(1);
    assertThat(meters.get("alerts.consumed").tag("outcome", "ignored").counter().count())
        .isGreaterThanOrEqualTo(1.0);
  }

  @Test
  void anOngoingConditionIsOneEpisodeThatNotifiesOnceAndKeepsCounting() throws Exception {
    UUID first = UUID.randomUUID();
    String patient = "P-1003";
    for (int minute = 0; minute < 4; minute++) {
      UUID detection = minute == 0 ? first : UUID.randomUUID();
      sendAlert(
          alertJson(
              detection,
              patient,
              "THRESHOLD",
              "MEDIUM",
              "HEART_RATE",
              "2026-11-11T20:1" + minute + ":00Z",
              "tachycardia sustained"),
          patient,
          "c" + minute);
    }

    UUID episode = awaitSingleEpisode(patient);
    awaitOccurrences(episode, 4);

    assertThat(countEpisodes(patient)).isEqualTo(1L);
    assertThat(rabbitMessagesFor(episode, Duration.ofSeconds(10))).hasSize(1);
    var view = json.readTree(get("/api/v1/alerts/" + episode).body());
    assertThat(view.path("occurrences").asInt()).isEqualTo(4);
    assertThat(view.path("firstSeenAt").asString()).isEqualTo("2026-11-11T20:10:00Z");
    assertThat(view.path("lastSeenAt").asString()).isEqualTo("2026-11-11T20:14:00Z");
    await()
        .atMost(Duration.ofSeconds(20))
        .until(() -> {
          var search =
              json.readTree(get("/api/v1/alerts?q=tachycardia&patientId=" + patient).body());
          return search.path("totalElements").asLong() == 1L
              && search.path("items").get(0).path("occurrences").asInt() == 4;
        });
    // 1 opening (index + notify) and 3 updates (index only)
    awaitOutboxDrained(episode);
    assertThat(outboxRows(episode)).isEqualTo(5L);
  }

  @Test
  void escalationNotifiesAgainAndEscalatesToSqsOnlyWhenCritical() throws Exception {
    UUID first = UUID.randomUUID();
    sendAlert(
        alertJson(
            first, "P-1005", "THRESHOLD", "MEDIUM", "RESP_RATE", "2026-11-11T20:15:00Z", "rr"),
        "P-1005",
        "c-e1");
    UUID episode = awaitEpisode(first);
    assertThat(rabbitMessagesFor(episode, Duration.ofSeconds(8))).hasSize(1);
    UUID raise = UUID.randomUUID();
    sendAlert(
        alertJson(
            raise, "P-1005", "THRESHOLD", "CRITICAL", "RESP_RATE", "2026-11-11T20:16:00Z", "rr"),
        "P-1005",
        "c-e2");

    await().atMost(Duration.ofSeconds(30)).until(() -> severityOf(episode).equals("CRITICAL"));
    var rabbit = rabbitMessagesFor(episode, Duration.ofSeconds(10));
    var sqs = sqsMessagesFor(episode, Duration.ofSeconds(5));

    assertThat(rabbit).hasSize(1);
    assertThat(rabbit.getFirst().getEnvelope().getRoutingKey()).isEqualTo("alert.critical");
    assertThat(sqs).hasSize(1);
    assertThat(countEpisodes("P-1005")).isEqualTo(1L);
  }

  private String severityOf(UUID episode) {
    return jdbc.sql("SELECT severity FROM clinical.alerts WHERE id = :id")
        .param("id", episode)
        .query(String.class)
        .single();
  }

  @Test
  void aFindingUnderAnActiveNews2IsGroupedQuietAndHiddenFromTheTopLevelList() throws Exception {
    String patient = "P-1006";
    UUID news2Detection = UUID.randomUUID();
    sendAlert(
        alertJson(
            news2Detection, patient, "NEWS2", "HIGH", null, "2026-11-11T20:15:00Z", "news2 7"),
        patient,
        "c-h1");
    UUID parent = awaitEpisode(news2Detection);
    UUID childDetection = UUID.randomUUID();
    sendAlert(
        alertJson(
            childDetection,
            patient,
            "THRESHOLD",
            "MEDIUM",
            "HEART_RATE",
            "2026-11-11T20:15:00Z",
            "hr high"),
        patient,
        "c-h2");
    UUID child = awaitEpisode(childDetection);

    assertThat(rabbitMessagesFor(parent, Duration.ofSeconds(8))).hasSize(1);
    assertThat(rabbitMessagesFor(child, Duration.ofSeconds(3))).isEmpty();
    var top = json.readTree(get("/api/v1/alerts?patientId=" + patient + "&topLevel=true").body());
    var children =
        json.readTree(
            get("/api/v1/alerts?patientId=" + patient + "&parentAlertId=" + parent).body());
    var parentView = json.readTree(get("/api/v1/alerts/" + parent).body());
    assertThat(top.path("totalElements").asLong()).isEqualTo(1L);
    assertThat(top.path("items").get(0).path("alertId").asString()).isEqualTo(parent.toString());
    assertThat(children.path("items")).hasSize(1);
    assertThat(children.path("items").get(0).path("alertId").asString())
        .isEqualTo(child.toString());
    assertThat(parentView.path("childCount").asInt()).isEqualTo(1);
    assertThat(alertRepository.openCount(patient)).isEqualTo(1L);
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
        .insert(argThat((Alert a) -> "P-FAIL".equals(a.patientId())));
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
        .insert(argThat((Alert a) -> "P-FAIL".equals(a.patientId())));
  }
}
