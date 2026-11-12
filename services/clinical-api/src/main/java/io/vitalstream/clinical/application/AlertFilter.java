package io.vitalstream.clinical.application;

import io.vitalstream.clinical.domain.AlertStatus;
import io.vitalstream.clinical.domain.AlertType;
import io.vitalstream.clinical.domain.Severity;
import java.util.List;
import java.util.UUID;

/**
 * Criteria for listing alert episodes. {@code q} switches the backing store from Postgres to
 * Elasticsearch because only the index can do full-text relevance search. An empty {@code statuses}
 * list means any status; {@code topLevelOnly} hides episodes grouped under a NEWS2 parent; {@code
 * parentAlertId} lists the children of one episode.
 */
public record AlertFilter(
    String patientId,
    Severity severity,
    List<AlertStatus> statuses,
    AlertType type,
    boolean topLevelOnly,
    UUID parentAlertId,
    String q,
    int page,
    int size) {

  public AlertFilter {
    statuses = statuses == null ? List.of() : List.copyOf(statuses);
  }

  public boolean hasTextQuery() {
    return q != null && !q.isBlank();
  }

  public int offset() {
    return page * size;
  }
}
