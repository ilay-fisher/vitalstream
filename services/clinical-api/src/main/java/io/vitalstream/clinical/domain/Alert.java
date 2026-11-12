package io.vitalstream.clinical.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Aggregate for one alert <em>episode</em>: an ongoing condition identified by patient, type and
 * metric (CONTRACTS section 3.4). Many per-window {@link Detection detections} fold into one
 * episode. Immutable: state changes return a new instance so that the persisted {@code version} is
 * the only thing that changes under optimistic locking.
 *
 * <p>{@code windowStart}, {@code windowEnd}, {@code detectedAt}, {@code value}, {@code score} and
 * {@code message} always describe the latest absorbed detection; {@code severity} is the peak.
 * {@code childCount} is read-only information filled by the store when loading.
 */
public record Alert(
    UUID id,
    String patientId,
    AlertType type,
    Severity severity,
    String metric,
    Double value,
    Double score,
    String message,
    Instant windowStart,
    Instant windowEnd,
    Instant detectedAt,
    AlertStatus status,
    String acknowledgedBy,
    Instant acknowledgedAt,
    String acknowledgementNote,
    long version,
    Instant firstSeenAt,
    Instant lastSeenAt,
    int occurrences,
    Instant resolvedAt,
    UUID parentAlertId,
    int childCount,
    UUID lastDetectionId) {

  public Alert {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(patientId, "patientId");
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(severity, "severity");
    Objects.requireNonNull(status, "status");
  }

  /** Opens an episode from its first detection: status OPEN, version 0, one occurrence. */
  public static Alert open(Detection d) {
    return new Alert(
        EpisodeIds.of(d.patientId(), d.type(), d.metric(), d.windowStart()),
        d.patientId(),
        d.type(),
        d.severity(),
        d.metric(),
        d.value(),
        d.score(),
        d.message(),
        d.windowStart(),
        d.windowEnd(),
        d.detectedAt(),
        AlertStatus.OPEN,
        null,
        null,
        null,
        0,
        d.windowStart(),
        d.windowEnd(),
        1,
        null,
        null,
        0,
        d.id());
  }

  /** Top-level episodes are the ones clinicians are paged for; children hang under a NEWS2. */
  public boolean isTopLevel() {
    return parentAlertId == null;
  }

  /**
   * Folds a detection of this episode's key into the episode.
   *
   * <ul>
   *   <li>older window than the latest one: ignored (late or replayed);
   *   <li>same window (Spark update mode re-emits): only a severity raise applies;
   *   <li>newer window: counted, latest-detection fields replaced, severity never lowered.
   * </ul>
   *
   * A severity raise is an escalation and re-opens an acknowledged episode, keeping the
   * acknowledgement fields for audit.
   *
   * @return empty when nothing changes
   */
  public Optional<Absorption> absorb(Detection d) {
    if (status == AlertStatus.RESOLVED || isBeforeLatestWindow(d)) {
      return Optional.empty();
    }
    boolean raised = d.severity().isHigherThan(severity);
    boolean sameWindow = d.windowStart().equals(windowStart);
    if (sameWindow && !raised) {
      return Optional.empty();
    }
    Builder next = toBuilder();
    if (raised) {
      next.severity = d.severity();
      next.status = AlertStatus.OPEN;
    }
    if (!sameWindow) {
      next.occurrences = occurrences + 1;
      next.lastSeenAt = d.windowEnd();
      next.value = d.value();
      next.score = d.score();
      next.message = d.message();
      next.windowStart = d.windowStart();
      next.windowEnd = d.windowEnd();
      next.detectedAt = d.detectedAt();
      next.lastDetectionId = d.id();
    }
    return Optional.of(
        new Absorption(next.build(), raised ? EpisodeChange.ESCALATED : EpisodeChange.UPDATED));
  }

  private boolean isBeforeLatestWindow(Detection d) {
    return windowStart != null && d.windowStart().isBefore(windowStart);
  }

  /** Copy carrying the version assigned by the store after a successful optimistic update. */
  public Alert withVersion(long newVersion) {
    Builder b = toBuilder();
    b.version = newVersion;
    return b.build();
  }

  /** Copy with another status (used by stores that resolve in bulk). */
  public Alert withStatus(AlertStatus newStatus) {
    Builder b = toBuilder();
    b.status = newStatus;
    return b.build();
  }

  /** Copy linked under {@code parent}, or top-level when null. */
  public Alert withParent(UUID parent) {
    Builder b = toBuilder();
    b.parentAlertId = parent;
    return b.build();
  }

  /** Copy carrying the number of non-resolved children, as counted by the store. */
  public Alert withChildCount(int children) {
    Builder b = toBuilder();
    b.childCount = children;
    return b.build();
  }

  /**
   * Closes the episode after its silence period. Stores apply the same rule in one bulk {@code
   * UPDATE} so that replicas never race; this is the executable form of it.
   *
   * @throws InvalidStatusTransitionException if the episode is already resolved
   */
  public Alert resolve(Instant at) {
    if (!status.canTransitionTo(AlertStatus.RESOLVED)) {
      throw new InvalidStatusTransitionException(status, AlertStatus.RESOLVED);
    }
    Builder b = toBuilder();
    b.status = AlertStatus.RESOLVED;
    b.resolvedAt = at;
    return b.build();
  }

  /**
   * Records a clinician acknowledgement.
   *
   * @throws InvalidStatusTransitionException if the episode is not OPEN
   */
  public Alert acknowledge(String by, String note, Instant at) {
    if (!status.canTransitionTo(AlertStatus.ACKNOWLEDGED)) {
      throw new InvalidStatusTransitionException(status, AlertStatus.ACKNOWLEDGED);
    }
    Builder b = toBuilder();
    b.status = AlertStatus.ACKNOWLEDGED;
    b.acknowledgedBy = by;
    b.acknowledgedAt = at;
    b.acknowledgementNote = note;
    return b.build();
  }

  private Builder toBuilder() {
    Builder b = new Builder();
    b.id = id;
    b.patientId = patientId;
    b.type = type;
    b.severity = severity;
    b.metric = metric;
    b.value = value;
    b.score = score;
    b.message = message;
    b.windowStart = windowStart;
    b.windowEnd = windowEnd;
    b.detectedAt = detectedAt;
    b.status = status;
    b.acknowledgedBy = acknowledgedBy;
    b.acknowledgedAt = acknowledgedAt;
    b.acknowledgementNote = acknowledgementNote;
    b.version = version;
    b.firstSeenAt = firstSeenAt;
    b.lastSeenAt = lastSeenAt;
    b.occurrences = occurrences;
    b.resolvedAt = resolvedAt;
    b.parentAlertId = parentAlertId;
    b.childCount = childCount;
    b.lastDetectionId = lastDetectionId;
    return b;
  }

  /** Mutable scratch copy so that the 23-field record is rebuilt in exactly one place. */
  private static final class Builder {
    UUID id;
    String patientId;
    AlertType type;
    Severity severity;
    String metric;
    Double value;
    Double score;
    String message;
    Instant windowStart;
    Instant windowEnd;
    Instant detectedAt;
    AlertStatus status;
    String acknowledgedBy;
    Instant acknowledgedAt;
    String acknowledgementNote;
    long version;
    Instant firstSeenAt;
    Instant lastSeenAt;
    int occurrences;
    Instant resolvedAt;
    UUID parentAlertId;
    int childCount;
    UUID lastDetectionId;

    Alert build() {
      return new Alert(
          id,
          patientId,
          type,
          severity,
          metric,
          value,
          score,
          message,
          windowStart,
          windowEnd,
          detectedAt,
          status,
          acknowledgedBy,
          acknowledgedAt,
          acknowledgementNote,
          version,
          firstSeenAt,
          lastSeenAt,
          occurrences,
          resolvedAt,
          parentAlertId,
          childCount,
          lastDetectionId);
    }
  }
}
