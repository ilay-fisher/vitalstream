package io.vitalstream.clinical.application;

import io.vitalstream.clinical.domain.Alert;
import io.vitalstream.clinical.domain.AlertStatus;
import io.vitalstream.clinical.domain.AlertType;
import io.vitalstream.clinical.domain.Severity;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Behavioural fake of the Postgres store: same compare-and-set, uniqueness and bulk-update rules as
 * {@code JdbcAlertRepository}, so the services can be tested without Docker. The SQL itself is
 * covered by the Testcontainers tests.
 */
class InMemoryAlertRepository implements AlertRepository {

  private final Map<UUID, Alert> rows = new LinkedHashMap<>();

  /** Number of upcoming compare-and-set writes that lose the race. */
  int failingWrites;

  Alert get(UUID id) {
    return withChildCount(rows.get(id));
  }

  List<Alert> all() {
    return rows.values().stream().map(this::withChildCount).toList();
  }

  /** Puts a row as is, for arranging states the services cannot reach directly. */
  void seed(Alert alert) {
    rows.put(alert.id(), alert);
  }

  @Override
  public Optional<Alert> findById(UUID id) {
    return Optional.ofNullable(rows.get(id)).map(this::withChildCount);
  }

  @Override
  public Optional<Alert> findActive(String patientId, AlertType type, String metric) {
    return rows.values().stream()
        .filter(a -> a.status() != AlertStatus.RESOLVED)
        .filter(a -> a.patientId().equals(patientId) && a.type() == type)
        .filter(a -> Objects.equals(a.metric() == null ? "" : a.metric(), metric == null ? "" : metric))
        .findFirst()
        .map(this::withChildCount);
  }

  @Override
  public boolean insert(Alert episode) {
    if (rows.containsKey(episode.id())
        || findActive(episode.patientId(), episode.type(), episode.metric()).isPresent()) {
      return false;
    }
    rows.put(episode.id(), episode);
    return true;
  }

  @Override
  public boolean update(Alert episode, long expectedVersion) {
    return compareAndSet(episode, expectedVersion);
  }

  @Override
  public boolean updateAcknowledgement(Alert acknowledged, long expectedVersion) {
    return compareAndSet(acknowledged, expectedVersion);
  }

  private boolean compareAndSet(Alert next, long expectedVersion) {
    Alert stored = rows.get(next.id());
    if (failingWrites > 0) {
      failingWrites--;
      return false;
    }
    if (stored == null || stored.version() != expectedVersion) {
      return false;
    }
    rows.put(next.id(), next.withVersion(expectedVersion + 1));
    return true;
  }

  @Override
  public List<UUID> linkChildren(String patientId, UUID parentId, Severity max) {
    List<UUID> linked = new ArrayList<>();
    for (Alert a : List.copyOf(rows.values())) {
      if (a.patientId().equals(patientId)
          && a.status() != AlertStatus.RESOLVED
          && a.type() != AlertType.NEWS2
          && a.parentAlertId() == null
          && !a.severity().isHigherThan(max)) {
        rows.put(a.id(), a.withParent(parentId).withVersion(a.version() + 1));
        linked.add(a.id());
      }
    }
    return linked;
  }

  @Override
  public List<UUID> unlinkChildren(Collection<UUID> parentIds) {
    List<UUID> unlinked = new ArrayList<>();
    for (Alert a : List.copyOf(rows.values())) {
      if (a.status() != AlertStatus.RESOLVED && parentIds.contains(a.parentAlertId())) {
        rows.put(a.id(), a.withParent(null).withVersion(a.version() + 1));
        unlinked.add(a.id());
      }
    }
    return unlinked;
  }

  @Override
  public void touch(UUID id) {
    Alert a = rows.get(id);
    rows.put(id, a.withVersion(a.version() + 1));
  }

  @Override
  public List<ResolvedEpisode> resolveStale(Instant cutoff, Instant resolvedAt) {
    List<ResolvedEpisode> resolved = new ArrayList<>();
    for (Alert a : List.copyOf(rows.values())) {
      if (a.status() != AlertStatus.RESOLVED && a.lastSeenAt().isBefore(cutoff)) {
        rows.put(a.id(), a.resolve(resolvedAt).withVersion(a.version() + 1));
        resolved.add(new ResolvedEpisode(a.id(), a.type(), a.parentAlertId()));
      }
    }
    return resolved;
  }

  @Override
  public PageResult<AlertView> search(AlertFilter filter) {
    throw new UnsupportedOperationException("covered by the Postgres integration tests");
  }

  @Override
  public Map<String, Long> openCountsByPatient() {
    throw new UnsupportedOperationException("covered by the Postgres integration tests");
  }

  @Override
  public long openCount(String patientId) {
    throw new UnsupportedOperationException("covered by the Postgres integration tests");
  }

  private Alert withChildCount(Alert a) {
    long children =
        rows.values().stream()
            .filter(c -> a.id().equals(c.parentAlertId()) && c.status() != AlertStatus.RESOLVED)
            .count();
    return a.withChildCount((int) children);
  }
}
