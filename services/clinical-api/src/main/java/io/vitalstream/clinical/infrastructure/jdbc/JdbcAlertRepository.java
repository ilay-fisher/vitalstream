package io.vitalstream.clinical.infrastructure.jdbc;

import io.vitalstream.clinical.application.AlertFilter;
import io.vitalstream.clinical.application.AlertRepository;
import io.vitalstream.clinical.application.AlertView;
import io.vitalstream.clinical.application.PageResult;
import io.vitalstream.clinical.application.ResolvedEpisode;
import io.vitalstream.clinical.domain.Alert;
import io.vitalstream.clinical.domain.AlertStatus;
import io.vitalstream.clinical.domain.AlertType;
import io.vitalstream.clinical.domain.Severity;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Postgres implementation of {@link AlertRepository}. Concurrency rules live in SQL: writes based
 * on a read compare the version, bulk changes are single conditional statements, and the partial
 * unique index {@code alerts_active_episode_uidx} keeps at most one non-resolved episode per key.
 */
@Repository
public class JdbcAlertRepository implements AlertRepository {

  private static final String SELECT =
      """
      SELECT a.id, a.patient_id, a.type, a.severity, a.metric, a.value, a.score, a.message,
             a.window_start, a.window_end, a.detected_at, a.status, a.acknowledged_by,
             a.acknowledged_at, a.ack_note, a.version, a.first_seen_at, a.last_seen_at,
             a.occurrences, a.resolved_at, a.parent_alert_id, a.last_detection_id,
             (SELECT count(*) FROM clinical.alerts c
               WHERE c.parent_alert_id = a.id AND c.status <> 'RESOLVED') AS child_count
        FROM clinical.alerts a
      """;

  private final JdbcClient jdbc;

  public JdbcAlertRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<Alert> findById(UUID id) {
    return jdbc.sql(SELECT + " WHERE a.id = :id")
        .param("id", id)
        .query(JdbcAlertRepository::mapAlert)
        .optional();
  }

  @Override
  public Optional<Alert> findActive(String patientId, AlertType type, String metric) {
    return jdbc.sql(
            SELECT
                + " WHERE a.patient_id = :patientId AND a.type = :type"
                + " AND coalesce(a.metric, '') = :metric AND a.status <> 'RESOLVED'")
        .param("patientId", patientId)
        .param("type", type.name())
        .param("metric", metric == null ? "" : metric)
        .query(JdbcAlertRepository::mapAlert)
        .optional();
  }

  @Override
  public boolean insert(Alert episode) {
    int rows =
        jdbc.sql(
                """
                INSERT INTO clinical.alerts
                  (id, patient_id, type, severity, metric, value, score, message,
                   window_start, window_end, detected_at, status, version,
                   first_seen_at, last_seen_at, occurrences, parent_alert_id, last_detection_id)
                VALUES
                  (:id, :patientId, :type, :severity, :metric, :value, :score, :message,
                   :windowStart, :windowEnd, :detectedAt, :status, 0,
                   :firstSeenAt, :lastSeenAt, :occurrences, :parent, :lastDetectionId)
                ON CONFLICT DO NOTHING
                """)
            .param("id", episode.id())
            .param("patientId", episode.patientId())
            .param("type", episode.type().name())
            .param("severity", episode.severity().name())
            .param("metric", episode.metric())
            .param("value", episode.value())
            .param("score", episode.score())
            .param("message", episode.message())
            .param("windowStart", Timestamps.toDb(episode.windowStart()))
            .param("windowEnd", Timestamps.toDb(episode.windowEnd()))
            .param("detectedAt", Timestamps.toDb(episode.detectedAt()))
            .param("status", episode.status().name())
            .param("firstSeenAt", Timestamps.toDb(episode.firstSeenAt()))
            .param("lastSeenAt", Timestamps.toDb(episode.lastSeenAt()))
            .param("occurrences", episode.occurrences())
            .param("parent", episode.parentAlertId())
            .param("lastDetectionId", episode.lastDetectionId())
            .update();
    return rows == 1;
  }

  @Override
  public boolean update(Alert episode, long expectedVersion) {
    int rows =
        jdbc.sql(
                """
                UPDATE clinical.alerts
                   SET severity = :severity, value = :value, score = :score, message = :message,
                       window_start = :windowStart, window_end = :windowEnd,
                       detected_at = :detectedAt, status = :status, last_seen_at = :lastSeenAt,
                       occurrences = :occurrences, parent_alert_id = :parent,
                       last_detection_id = :lastDetectionId, version = version + 1
                 WHERE id = :id AND version = :expected
                """)
            .param("severity", episode.severity().name())
            .param("value", episode.value())
            .param("score", episode.score())
            .param("message", episode.message())
            .param("windowStart", Timestamps.toDb(episode.windowStart()))
            .param("windowEnd", Timestamps.toDb(episode.windowEnd()))
            .param("detectedAt", Timestamps.toDb(episode.detectedAt()))
            .param("status", episode.status().name())
            .param("lastSeenAt", Timestamps.toDb(episode.lastSeenAt()))
            .param("occurrences", episode.occurrences())
            .param("parent", episode.parentAlertId())
            .param("lastDetectionId", episode.lastDetectionId())
            .param("id", episode.id())
            .param("expected", expectedVersion)
            .update();
    return rows == 1;
  }

  @Override
  public boolean updateAcknowledgement(Alert acknowledged, long expectedVersion) {
    int rows =
        jdbc.sql(
                """
                UPDATE clinical.alerts
                   SET status = :status, acknowledged_by = :by, acknowledged_at = :at,
                       ack_note = :note, version = version + 1
                 WHERE id = :id AND version = :expected
                """)
            .param("status", acknowledged.status().name())
            .param("by", acknowledged.acknowledgedBy())
            .param("at", Timestamps.toDb(acknowledged.acknowledgedAt()))
            .param("note", acknowledged.acknowledgementNote())
            .param("id", acknowledged.id())
            .param("expected", expectedVersion)
            .update();
    return rows == 1;
  }

  @Override
  public List<UUID> linkChildren(String patientId, UUID parentId, Severity max) {
    return jdbc.sql(
            """
            UPDATE clinical.alerts
               SET parent_alert_id = :parent, version = version + 1
             WHERE patient_id = :patientId AND status <> 'RESOLVED' AND type <> 'NEWS2'
               AND parent_alert_id IS NULL AND severity IN (:severities)
            RETURNING id
            """)
        .param("parent", parentId)
        .param("patientId", patientId)
        .param("severities", Severity.upTo(max).stream().map(Severity::name).toList())
        .query(UUID.class)
        .list();
  }

  @Override
  public List<UUID> unlinkChildren(Collection<UUID> parentIds) {
    if (parentIds.isEmpty()) {
      return List.of();
    }
    return jdbc.sql(
            """
            UPDATE clinical.alerts
               SET parent_alert_id = NULL, version = version + 1
             WHERE parent_alert_id IN (:parents) AND status <> 'RESOLVED'
            RETURNING id
            """)
        .param("parents", parentIds)
        .query(UUID.class)
        .list();
  }

  @Override
  public void touch(UUID id) {
    jdbc.sql("UPDATE clinical.alerts SET version = version + 1 WHERE id = :id")
        .param("id", id)
        .update();
  }

  @Override
  public List<ResolvedEpisode> resolveStale(Instant cutoff, Instant resolvedAt) {
    return jdbc.sql(
            """
            UPDATE clinical.alerts
               SET status = 'RESOLVED', resolved_at = :resolvedAt, version = version + 1
             WHERE status <> 'RESOLVED' AND last_seen_at < :cutoff
            RETURNING id, type, parent_alert_id
            """)
        .param("resolvedAt", Timestamps.toDb(resolvedAt))
        .param("cutoff", Timestamps.toDb(cutoff))
        .query((rs, row) ->
            new ResolvedEpisode(
                rs.getObject("id", UUID.class),
                AlertType.valueOf(rs.getString("type")),
                rs.getObject("parent_alert_id", UUID.class)))
        .list();
  }

  @Override
  public PageResult<AlertView> search(AlertFilter filter) {
    Map<String, Object> params = new HashMap<>();
    StringBuilder where = new StringBuilder(" WHERE 1=1");
    if (filter.patientId() != null) {
      where.append(" AND a.patient_id = :patientId");
      params.put("patientId", filter.patientId());
    }
    if (filter.severity() != null) {
      where.append(" AND a.severity = :severity");
      params.put("severity", filter.severity().name());
    }
    if (!filter.statuses().isEmpty()) {
      where.append(" AND a.status IN (:statuses)");
      params.put("statuses", filter.statuses().stream().map(AlertStatus::name).toList());
    }
    if (filter.type() != null) {
      where.append(" AND a.type = :type");
      params.put("type", filter.type().name());
    }
    if (filter.topLevelOnly()) {
      where.append(" AND a.parent_alert_id IS NULL");
    }
    if (filter.parentAlertId() != null) {
      where.append(" AND a.parent_alert_id = :parentAlertId");
      params.put("parentAlertId", filter.parentAlertId());
    }
    long total =
        jdbc.sql("SELECT count(*) FROM clinical.alerts a" + where)
            .params(params)
            .query(Long.class)
            .single();
    params.put("limit", filter.size());
    params.put("offset", filter.offset());
    List<AlertView> items =
        jdbc
            .sql(SELECT + where + " ORDER BY a.last_seen_at DESC, a.id LIMIT :limit OFFSET :offset")
            .params(params)
            .query(JdbcAlertRepository::mapAlert)
            .list()
            .stream()
            .map(AlertView::from)
            .toList();
    return new PageResult<>(items, filter.page(), filter.size(), total);
  }

  @Override
  public Map<String, Long> openCountsByPatient() {
    return jdbc
        .sql(
            "SELECT patient_id, count(*) AS n FROM clinical.alerts"
                + " WHERE status IN ('OPEN', 'ACKNOWLEDGED') AND parent_alert_id IS NULL"
                + " GROUP BY patient_id")
        .query((rs, row) -> Map.entry(rs.getString("patient_id"), rs.getLong("n")))
        .list()
        .stream()
        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
  }

  @Override
  public long openCount(String patientId) {
    return jdbc.sql(
            "SELECT count(*) FROM clinical.alerts WHERE patient_id = :p"
                + " AND status IN ('OPEN', 'ACKNOWLEDGED') AND parent_alert_id IS NULL")
        .param("p", patientId)
        .query(Long.class)
        .single();
  }

  private static Alert mapAlert(ResultSet rs, int rowNum) throws SQLException {
    return new Alert(
        rs.getObject("id", UUID.class),
        rs.getString("patient_id"),
        AlertType.valueOf(rs.getString("type")),
        Severity.valueOf(rs.getString("severity")),
        rs.getString("metric"),
        rs.getObject("value", Double.class),
        rs.getObject("score", Double.class),
        rs.getString("message"),
        Timestamps.read(rs, "window_start"),
        Timestamps.read(rs, "window_end"),
        Timestamps.read(rs, "detected_at"),
        AlertStatus.valueOf(rs.getString("status")),
        rs.getString("acknowledged_by"),
        Timestamps.read(rs, "acknowledged_at"),
        rs.getString("ack_note"),
        rs.getLong("version"),
        Timestamps.read(rs, "first_seen_at"),
        Timestamps.read(rs, "last_seen_at"),
        rs.getInt("occurrences"),
        Timestamps.read(rs, "resolved_at"),
        rs.getObject("parent_alert_id", UUID.class),
        rs.getInt("child_count"),
        rs.getObject("last_detection_id", UUID.class));
  }
}
