package io.vitalstream.clinical.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.vitalstream.clinical.application.AlertView;
import io.vitalstream.clinical.application.OutboxPayloads;
import io.vitalstream.clinical.domain.AlertStatus;
import io.vitalstream.clinical.domain.Severity;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * V2 on a database that already holds per-detection rows (V1 schema): duplicates per key collapse
 * into one open episode before the partial unique index is created.
 */
@Tag("integration")
class EpisodeMigrationIT {

  private DataSource source;
  private JdbcClient jdbc;

  @BeforeEach
  void legacyDatabase() {
    source = PostgresSupport.freshDatabase();
    PostgresSupport.migrate(source, "1");
    jdbc = PostgresSupport.client(source);
  }

  /** One legacy per-detection row: windowStart is the minute after 20:00. */
  private UUID legacy(
      String patient, String type, String metric, String severity, int minute, String status) {
    UUID id = UUID.randomUUID();
    jdbc.sql(
            """
            INSERT INTO clinical.alerts
              (id, patient_id, type, severity, metric, value, score, message, window_start,
               window_end, detected_at, status, acknowledged_by, acknowledged_at, ack_note, version)
            VALUES
              (:id, :patient, :type, :severity, :metric, 100.0, NULL, :message,
               timestamptz '2026-11-11 20:00:00+00' + make_interval(mins => :minute),
               timestamptz '2026-11-11 20:01:00+00' + make_interval(mins => :minute),
               timestamptz '2026-11-11 20:01:02+00' + make_interval(mins => :minute),
               :status, :by, :at, :note, 2)
            """)
        .param("id", id)
        .param("patient", patient)
        .param("type", type)
        .param("severity", severity)
        .param("metric", metric)
        .param("message", "detection " + minute)
        .param("minute", minute)
        .param("status", status)
        .param("by", "ACKNOWLEDGED".equals(status) ? "dr.cohen" : null)
        .param(
            "at",
            "ACKNOWLEDGED".equals(status)
                ? java.time.OffsetDateTime.parse("2026-11-11T20:30:00Z")
                : null)
        .param("note", "ACKNOWLEDGED".equals(status) ? "seen" : null)
        .update();
    return id;
  }

  private void migrate() {
    PostgresSupport.migrate(source, null);
  }

  private String status(UUID id) {
    return jdbc.sql("SELECT status FROM clinical.alerts WHERE id = :id")
        .param("id", id)
        .query(String.class)
        .single();
  }

  @Test
  void theNewestRowOfAKeyStaysOpenWithTheRowCountAndOlderRowsResolve() {
    UUID oldest = legacy("P-M1", "THRESHOLD", "HEART_RATE", "MEDIUM", 0, "OPEN");
    UUID middle = legacy("P-M1", "THRESHOLD", "HEART_RATE", "HIGH", 1, "ACKNOWLEDGED");
    UUID newest = legacy("P-M1", "THRESHOLD", "HEART_RATE", "MEDIUM", 2, "OPEN");
    UUID otherKey = legacy("P-M1", "THRESHOLD", "SPO2", "MEDIUM", 0, "OPEN");
    UUID otherPatient = legacy("P-M2", "THRESHOLD", "HEART_RATE", "MEDIUM", 0, "OPEN");

    migrate();

    assertThat(status(newest)).isEqualTo("OPEN");
    assertThat(status(oldest)).isEqualTo("RESOLVED");
    assertThat(status(middle)).isEqualTo("RESOLVED");
    assertThat(status(otherKey)).isEqualTo("OPEN");
    assertThat(status(otherPatient)).isEqualTo("OPEN");
    assertThat(occurrences(newest)).isEqualTo(3);
    assertThat(occurrences(otherKey)).isEqualTo(1);
    assertThat(
            jdbc.sql("SELECT resolved_at IS NOT NULL FROM clinical.alerts WHERE id = :id")
                .param("id", oldest)
                .query(Boolean.class)
                .single())
        .isTrue();
    assertThat(
            jdbc.sql("SELECT resolved_at IS NULL FROM clinical.alerts WHERE id = :id")
                .param("id", newest)
                .query(Boolean.class)
                .single())
        .isTrue();
  }

  private int occurrences(UUID id) {
    return jdbc.sql("SELECT occurrences FROM clinical.alerts WHERE id = :id")
        .param("id", id)
        .query(Integer.class)
        .single();
  }

  @Test
  void rowsWithoutAMetricShareAKeyAndAcknowledgementsSurvive() {
    UUID first = legacy("P-M3", "NEWS2", null, "HIGH", 0, "ACKNOWLEDGED");
    UUID second = legacy("P-M3", "NEWS2", null, "CRITICAL", 1, "ACKNOWLEDGED");

    migrate();

    assertThat(status(second)).isEqualTo("ACKNOWLEDGED");
    assertThat(status(first)).isEqualTo("RESOLVED");
    assertThat(occurrences(second)).isEqualTo(2);
    assertThat(
            jdbc.sql("SELECT acknowledged_by FROM clinical.alerts WHERE id = :id")
                .param("id", first)
                .query(String.class)
                .single())
        .as("audit fields are kept on resolved rows")
        .isEqualTo("dr.cohen");
  }

  @Test
  void everyRowGetsItsWindowAsFirstAndLastSeenAndItselfAsLastDetection() {
    UUID id = legacy("P-M4", "TREND_ZSCORE", "SPO2", "MEDIUM", 5, "OPEN");

    migrate();

    var row =
        jdbc.sql(
                """
                SELECT first_seen_at = window_start AS first_ok, last_seen_at = window_end AS last_ok,
                       last_detection_id = id AS detection_ok, parent_alert_id IS NULL AS top_level,
                       version
                  FROM clinical.alerts WHERE id = :id
                """)
            .param("id", id)
            .query((rs, n) ->
                List.of(
                    rs.getBoolean("first_ok"),
                    rs.getBoolean("last_ok"),
                    rs.getBoolean("detection_ok"),
                    rs.getBoolean("top_level"),
                    rs.getLong("version") == 3))
            .single();
    assertThat(row).containsOnly(true);
  }

  @Test
  void thePartialUniqueIndexExistsAfterMigration() {
    legacy("P-M5", "NEWS2", null, "HIGH", 0, "OPEN");
    legacy("P-M5", "NEWS2", null, "HIGH", 1, "OPEN");

    migrate();

    assertThat(
            jdbc.sql(
                    "SELECT indexdef FROM pg_indexes WHERE indexname = 'alerts_active_episode_uidx'")
                .query(String.class)
                .single())
        .contains("UNIQUE")
        .contains("COALESCE(metric, ''::text)")
        .contains("status <> 'RESOLVED'");
    assertThat(
            jdbc.sql(
                    "SELECT count(*) FROM clinical.alerts WHERE patient_id = 'P-M5' AND status <> 'RESOLVED'")
                .query(Long.class)
                .single())
        .isEqualTo(1L);
  }

  @Test
  void everyMigratedRowIsQueuedForReindexingWithAReadablePayload() {
    UUID newest = legacy("P-M6", "THRESHOLD", "HEART_RATE", "MEDIUM", 1, "OPEN");
    UUID older = legacy("P-M6", "THRESHOLD", "HEART_RATE", "MEDIUM", 0, "OPEN");

    migrate();

    List<String> payloads =
        jdbc.sql(
                "SELECT payload::text FROM clinical.outbox WHERE event_type = 'ALERT_INDEX'"
                    + " AND aggregate_id IN (:ids)")
            .param("ids", List.of(newest.toString(), older.toString()))
            .query(String.class)
            .list();
    OutboxPayloads reader = new OutboxPayloads(JsonMapper.builder().build());
    assertThat(payloads).hasSize(2);
    List<AlertView> views = payloads.stream().map(p -> reader.read(p).alert()).toList();
    AlertView kept =
        views.stream().filter(v -> v.alertId().equals(newest)).findFirst().orElseThrow();
    AlertView resolved =
        views.stream().filter(v -> v.alertId().equals(older)).findFirst().orElseThrow();
    assertThat(kept.status()).isEqualTo(AlertStatus.OPEN);
    assertThat(kept.occurrences()).isEqualTo(2);
    assertThat(kept.severity()).isEqualTo(Severity.MEDIUM);
    assertThat(kept.version()).isEqualTo(3);
    assertThat(kept.firstSeenAt()).isEqualTo(kept.windowStart());
    assertThat(kept.lastSeenAt()).isEqualTo(kept.windowEnd());
    assertThat(kept.lastDetectionId()).isEqualTo(newest);
    assertThat(kept.parentAlertId()).isNull();
    assertThat(resolved.status()).isEqualTo(AlertStatus.RESOLVED);
    assertThat(resolved.resolvedAt()).isNotNull();
    assertThat(reader.read(payloads.getFirst()).correlationId()).isEqualTo("migration-v2");
  }

  @Test
  void migratingAnEmptyDatabaseWorks() {
    migrate();

    assertThat(jdbc.sql("SELECT count(*) FROM clinical.alerts").query(Long.class).single())
        .isZero();
  }
}
