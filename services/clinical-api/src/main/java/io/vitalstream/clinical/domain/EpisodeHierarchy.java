package io.vitalstream.clinical.domain;

import java.util.Optional;
import java.util.UUID;

/**
 * NEWS2 is the parent of the other detector families (CONTRACTS section 3.4): one deterioration
 * should read as one NEWS2 episode with its supporting findings underneath, not as four alarms.
 */
public final class EpisodeHierarchy {

  private EpisodeHierarchy() {}

  /**
   * The parent an episode must have right now, given the patient's active NEWS2 episode.
   *
   * <p>A child that outranks its parent is a finding the NEWS2 score understates, so it stays
   * top-level and keeps paging. Calling this again after each update re-checks escalation.
   *
   * @return the NEWS2 episode id, or null for a top-level episode
   */
  public static UUID parentFor(Alert episode, Optional<Alert> activeNews2) {
    if (episode.type() == AlertType.NEWS2 || activeNews2.isEmpty()) {
      return null;
    }
    Alert parent = activeNews2.get();
    boolean adopts =
        parent.status() != AlertStatus.RESOLVED
            && !episode.severity().isHigherThan(parent.severity());
    return adopts ? parent.id() : null;
  }
}
