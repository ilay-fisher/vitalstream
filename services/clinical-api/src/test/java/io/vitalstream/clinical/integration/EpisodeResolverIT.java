package io.vitalstream.clinical.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.json.JsonMapper;

/**
 * Auto-resolution through the wired application. The test windows lie in the past, so the resolver
 * (periodic runs are disabled in this context) considers them silent for longer than PT5M.
 */
class EpisodeResolverIT extends IntegrationTestBase {

  @Autowired MeterRegistry meters;
  @Autowired org.springframework.data.redis.core.StringRedisTemplate redis;

  private final JsonMapper json = JsonMapper.builder().build();

  private UUID send(
      String patient, String type, String severity, String metric, String window, String text) {
    UUID detection = UUID.randomUUID();
    sendAlert(
        alertJson(detection, patient, type, severity, metric, window, text), patient, "c-res");
    return awaitEpisode(detection);
  }

  @Test
  void silentEpisodeResolvesIsReindexedAndStaysQuiet() throws Exception {
    UUID episode =
        send(
            "P-4001",
            "THRESHOLD",
            "HIGH",
            "HEART_RATE",
            "2026-11-11T20:15:00Z",
            "stale bradycardia");
    assertThat(rabbitMessagesFor(episode, Duration.ofSeconds(8))).hasSize(1);

    resolver().resolveStale();

    var view = json.readTree(get("/api/v1/alerts/" + episode).body());
    assertThat(view.path("status").asString()).isEqualTo("RESOLVED");
    assertThat(view.path("resolvedAt").asString()).isNotEmpty();
    assertThat(rabbitMessagesFor(episode, Duration.ofSeconds(4)))
        .as("resolution never notifies")
        .isEmpty();
    await()
        .atMost(Duration.ofSeconds(20))
        .until(() ->
            json.readTree(
                        get("/api/v1/alerts?q=bradycardia&status=RESOLVED&patientId=P-4001").body())
                    .path("totalElements")
                    .asLong()
                == 1L);
    assertThat(alertRepository.openCount("P-4001")).isZero();
    assertThat(
            meters
                .get("vitalstream_alert_episodes")
                .tag("event", "resolved")
                .tag("type", "THRESHOLD")
                .counter()
                .count())
        .isGreaterThanOrEqualTo(1.0);
  }

  @Test
  void aDetectionAfterResolutionOpensANewEpisodeThatNotifiesAgain() throws Exception {
    UUID old = send("P-4002", "THRESHOLD", "HIGH", "SPO2", "2026-11-11T20:15:00Z", "desaturation");
    resolver().resolveStale();
    assertThat(
            jdbc.sql("SELECT status FROM clinical.alerts WHERE id = :id")
                .param("id", old)
                .query(String.class)
                .single())
        .isEqualTo("RESOLVED");

    // a real gap before the next episode outlasts the suppression window; the test clock cannot
    redis.delete("alert:suppress:P-4002:THRESHOLD:SPO2");
    UUID fresh =
        send("P-4002", "THRESHOLD", "HIGH", "SPO2", "2026-11-11T21:15:00Z", "desaturation again");

    assertThat(fresh).isNotEqualTo(old);
    assertThat(countEpisodes("P-4002")).isEqualTo(2L);
    assertThat(rabbitMessagesFor(fresh, Duration.ofSeconds(10))).hasSize(1);
  }

  @Test
  void resolvingTheParentUnlinksTheChildThatOutlivesIt() throws Exception {
    String patient = "P-4003";
    UUID parent = send(patient, "NEWS2", "HIGH", null, "2026-11-11T20:15:00Z", "news2");
    UUID child = send(patient, "THRESHOLD", "MEDIUM", "HEART_RATE", "2026-11-11T20:15:00Z", "hr");
    jdbc.sql("UPDATE clinical.alerts SET last_seen_at = now() WHERE id = :id")
        .param("id", child)
        .update();

    assertThat(rabbitMessagesFor(child, Duration.ofSeconds(3))).as("quiet while grouped").isEmpty();

    resolver().resolveStale();
    resolver().resolveStale();

    assertThat(rabbitMessagesFor(child, Duration.ofSeconds(8)))
        .as("pages once when unlinked")
        .hasSize(1);
    var childView = json.readTree(get("/api/v1/alerts/" + child).body());
    assertThat(childView.path("status").asString()).isEqualTo("OPEN");
    assertThat(childView.path("parentAlertId").isNull()).isTrue();
    assertThat(json.readTree(get("/api/v1/alerts/" + parent).body()).path("status").asString())
        .isEqualTo("RESOLVED");
  }

  @Test
  void runningTheResolverRepeatedlyIsHarmless() {
    UUID episode = send("P-4004", "ML_ANOMALY", "MEDIUM", null, "2026-11-11T20:15:00Z", "anomaly");

    resolver().resolveStale();
    resolver().resolveStale();

    assertThat(
            jdbc.sql("SELECT version FROM clinical.alerts WHERE id = :id")
                .param("id", episode)
                .query(Long.class)
                .single())
        .isEqualTo(1L);
  }

  @Test
  void anAcknowledgedChildUnlinkedByItsParentDoesNotPage() throws Exception {
    String patient = "P-4005";
    send(patient, "NEWS2", "HIGH", null, "2026-11-11T20:15:00Z", "news2");
    UUID child = send(patient, "THRESHOLD", "MEDIUM", "HEART_RATE", "2026-11-11T20:15:00Z", "hr");
    assertThat(postJson("/api/v1/alerts/" + child + "/ack", "{}").statusCode()).isEqualTo(200);
    jdbc.sql("UPDATE clinical.alerts SET last_seen_at = now() WHERE id = :id")
        .param("id", child)
        .update();

    resolver().resolveStale();

    assertThat(rabbitMessagesFor(child, Duration.ofSeconds(5))).isEmpty();
    assertThat(json.readTree(get("/api/v1/alerts/" + child).body()).path("parentAlertId").isNull())
        .isTrue();
  }
}
