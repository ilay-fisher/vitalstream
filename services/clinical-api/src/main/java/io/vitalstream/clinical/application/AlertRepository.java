package io.vitalstream.clinical.application;

import io.vitalstream.clinical.domain.Alert;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Port: durable storage of alerts (Postgres). */
public interface AlertRepository {

  /**
   * Inserts the alert unless one with the same id exists, which makes Kafka replays harmless.
   *
   * @return true if a new row was written
   */
  boolean insertIfAbsent(Alert alert);

  Optional<Alert> findById(UUID id);

  PageResult<AlertView> search(AlertFilter filter);

  /**
   * Persists an acknowledgement only if the stored version still equals {@code expectedVersion},
   * and bumps the version.
   *
   * @return false when another writer got there first
   */
  boolean updateAcknowledgement(Alert acknowledged, long expectedVersion);

  /** Number of OPEN alerts per patient, for the patient list. */
  Map<String, Long> openCountsByPatient();

  long openCount(String patientId);
}
