package io.vitalstream.clinical.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import io.vitalstream.clinical.application.AlertEvent;
import io.vitalstream.clinical.domain.AlertType;
import io.vitalstream.clinical.domain.Severity;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.json.JsonMapper;

/**
 * Staleness rule (CONTRACTS section 3.4) against real Postgres, outbox relay and RabbitMQ. The
 * shared context runs with a huge ALERT_RESOLVE_AFTER, so the intake under test is built from the
 * wired collaborators with the production PT5M.
 */
class StaleNotificationIT extends IntegrationTestBase {

  @Autowired MeterRegistry meters;

  private final JsonMapper json = JsonMapper.builder().build();

  private static AlertEvent detection(String patient, Instant windowEnd) {
    return new AlertEvent(
        UUID.randomUUID(),
        patient,
        AlertType.THRESHOLD,
        Severity.HIGH,
        "HEART_RATE",
        142.0,
        null,
        "replayed tachycardia",
        windowEnd.minusSeconds(60),
        windowEnd,
        windowEnd.plusSeconds(2));
  }

  private List<String> outboxTypes(UUID episode) {
    return jdbc.sql(
            "SELECT event_type FROM clinical.outbox WHERE aggregate_id = :id ORDER BY created_at")
        .param("id", episode.toString())
        .query(String.class)
        .list();
  }

  private double skipped() {
    var counter = meters.find("vitalstream.alert.notifications.skipped").tag("reason", "stale");
    return counter.counter() == null ? 0.0 : counter.counter().count();
  }

  @Test
  void replayedOldDetectionOpensTheEpisodeButNeverPages() throws Exception {
    double before = skipped();

    ingestService()
        .ingest(detection("P-5001", Instant.now().minus(Duration.ofHours(1))), "c-stale");

    UUID stale = awaitSingleEpisode("P-5001");
    assertThat(outboxTypes(stale)).containsExactly("ALERT_INDEX");
    assertThat(rabbitMessagesFor(stale, Duration.ofSeconds(4))).isEmpty();
    assertThat(skipped() - before).isEqualTo(1.0);
    assertThat(json.readTree(get("/api/v1/alerts/" + stale).body()).path("status").asString())
        .isEqualTo("OPEN");
  }

  @Test
  void aCurrentDetectionStillPagesThroughTheSameIntake() throws Exception {
    ingestService().ingest(detection("P-5002", Instant.now()), "c-fresh");

    UUID fresh = awaitSingleEpisode("P-5002");

    assertThat(outboxTypes(fresh)).containsExactly("ALERT_INDEX", "ALERT_NOTIFY");
    assertThat(rabbitMessagesFor(fresh, Duration.ofSeconds(10))).hasSize(1);
  }
}
