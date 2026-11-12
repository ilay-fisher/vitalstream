package io.vitalstream.clinical.application;

import io.vitalstream.clinical.domain.Alert;
import io.vitalstream.clinical.domain.AlertType;
import io.vitalstream.clinical.domain.Severity;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Port: durable storage of alert episodes (Postgres). Every write that changes an episode bumps its
 * {@code version}; writes based on a read are compare-and-set on that version.
 */
public interface AlertRepository {

  Optional<Alert> findById(UUID id);

  /** The non-resolved episode of the key, if any ({@code metric} null and "" are the same key). */
  Optional<Alert> findActive(String patientId, AlertType type, String metric);

  /**
   * Inserts a new episode.
   *
   * @return false when nothing was written: the id exists (a replay) or the key already has a
   *     non-resolved episode (a concurrent writer won)
   */
  boolean insert(Alert episode);

  /**
   * Persists the detection-driven fields of an episode (severity, latest detection, status, counts,
   * parent) if the stored version still equals {@code expectedVersion}, and bumps the version.
   *
   * @return false when another writer got there first
   */
  boolean update(Alert episode, long expectedVersion);

  /**
   * Persists an acknowledgement only if the stored version still equals {@code expectedVersion},
   * and bumps the version.
   *
   * @return false when another writer got there first
   */
  boolean updateAcknowledgement(Alert acknowledged, long expectedVersion);

  /**
   * Links the patient's non-resolved, parentless, non-NEWS2 episodes whose severity is at most
   * {@code max} under {@code parentId}, bumping their versions.
   *
   * @return ids of the episodes that were linked
   */
  List<UUID> linkChildren(String patientId, UUID parentId, Severity max);

  /**
   * Makes the non-resolved children of the given parents top-level again, bumping their versions.
   *
   * @return ids of the episodes that were unlinked
   */
  List<UUID> unlinkChildren(Collection<UUID> parentIds);

  /** Bumps the version of an episode whose derived view changed (for example its child count). */
  void touch(UUID id);

  /**
   * Sets or clears the owed-notification flag (CONTRACTS section 3.4, Staleness) without changing
   * the version: the flag is internal bookkeeping and not part of the {@code AlertView}.
   */
  void setNotifyPending(UUID id, boolean pending);

  /**
   * Resolves, in one statement, every non-resolved episode whose {@code lastSeenAt} is before
   * {@code cutoff}. A single conditional update is what makes concurrent resolvers on several
   * replicas safe: the loser re-evaluates the row and finds nothing to do.
   */
  List<ResolvedEpisode> resolveStale(Instant cutoff, Instant resolvedAt);

  /** Episodes matching the filter, most recently seen first. */
  PageResult<AlertView> search(AlertFilter filter);

  /** Number of top-level OPEN or ACKNOWLEDGED episodes per patient, for the patient list. */
  Map<String, Long> openCountsByPatient();

  long openCount(String patientId);
}
