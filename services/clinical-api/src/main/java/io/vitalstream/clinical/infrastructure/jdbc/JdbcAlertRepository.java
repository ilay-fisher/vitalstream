package io.vitalstream.clinical.infrastructure.jdbc;

import io.vitalstream.clinical.application.AlertFilter;
import io.vitalstream.clinical.application.AlertRepository;
import io.vitalstream.clinical.application.AlertView;
import io.vitalstream.clinical.application.PageResult;
import io.vitalstream.clinical.domain.Alert;
import io.vitalstream.clinical.domain.AlertStatus;
import io.vitalstream.clinical.domain.AlertType;
import io.vitalstream.clinical.domain.Severity;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Postgres implementation of {@link AlertRepository}. */
@Repository
public class JdbcAlertRepository implements AlertRepository {

  private static final String COLUMNS =
      "id, patient_id, type, severity, metric, value, score, message, window_start, window_end,"
          + " detected_at, status, acknowledged_by, acknowledged_at, ack_note, version";

  private final JdbcClient jdbc;

  public JdbcAlertRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public boolean insertIfAbsent(Alert alert) {
    int rows =
        jdbc.sql(
                """
                INSERT INTO clinical.alerts
                  (id, patient_id, type, severity, metric, value, score, message,
                   window_start, window_end, detected_at, status, version)
                VALUES
                  (:id, :patientId, :type, :severity, :metric, :value, :score, :message,
                   :windowStart, :windowEnd, :detectedAt, :status, 0)
                ON CONFLICT (id) DO NOTHING
                """)
            .param("id", alert.id())
            .param("patientId", alert.patientId())
            .param("type", alert.type().name())
            .param("severity", alert.severity().name())
            .param("metric", alert.metric())
            .param("value", alert.value())
            .param("score", alert.score())
            .param("message", alert.message())
            .param("windowStart", Timestamps.toDb(alert.windowStart()))
            .param("windowEnd", Timestamps.toDb(alert.windowEnd()))
            .param("detectedAt", Timestamps.toDb(alert.detectedAt()))
            .param("status", alert.status().name())
            .update();
    return rows == 1;
  }

  @Override
  public Optional<Alert> findById(UUID id) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM clinical.alerts WHERE id = :id")
        .param("id", id)
        .query(JdbcAlertRepository::mapAlert)
        .optional();
  }

  @Override
  public PageResult<AlertView> search(AlertFilter filter) {
    Map<String, Object> params = new HashMap<>();
    StringBuilder where = new StringBuilder(" WHERE 1=1");
    if (filter.patientId() != null) {
      where.append(" AND patient_id = :patientId");
      params.put("patientId", filter.patientId());
    }
    if (filter.severity() != null) {
      where.append(" AND severity = :severity");
      params.put("severity", filter.severity().name());
    }
    if (filter.status() != null) {
      where.append(" AND status = :status");
      params.put("status", filter.status().name());
    }
    long total =
        jdbc.sql("SELECT count(*) FROM clinical.alerts" + where)
            .params(params)
            .query(Long.class)
            .single();
    params.put("limit", filter.size());
    params.put("offset", filter.offset());
    List<AlertView> items =
        jdbc
            .sql(
                "SELECT "
                    + COLUMNS
                    + " FROM clinical.alerts"
                    + where
                    + " ORDER BY detected_at DESC, id LIMIT :limit OFFSET :offset")
            .params(params)
            .query(JdbcAlertRepository::mapAlert)
            .list()
            .stream()
            .map(AlertView::from)
            .toList();
    return new PageResult<>(items, filter.page(), filter.size(), total);
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
  public Map<String, Long> openCountsByPatient() {
    return jdbc
        .sql(
            "SELECT patient_id, count(*) AS n FROM clinical.alerts WHERE status = 'OPEN'"
                + " GROUP BY patient_id")
        .query((rs, row) -> Map.entry(rs.getString("patient_id"), rs.getLong("n")))
        .list()
        .stream()
        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
  }

  @Override
  public long openCount(String patientId) {
    return jdbc.sql(
            "SELECT count(*) FROM clinical.alerts WHERE patient_id = :p AND status = 'OPEN'")
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
        rs.getLong("version"));
  }
}
