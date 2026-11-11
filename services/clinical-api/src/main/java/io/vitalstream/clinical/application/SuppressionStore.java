package io.vitalstream.clinical.application;

import java.time.Duration;

/** Port: shared (cross-replica) notification suppression windows. */
public interface SuppressionStore {

  /**
   * Claims the suppression window for {@code key}.
   *
   * @param owner id of the alert claiming it; re-claiming with the same owner succeeds so a retried
   *     delivery of the same alert is not mistaken for a duplicate
   * @return true if the caller may notify, false if an earlier alert already did within the window
   */
  boolean tryAcquire(String key, String owner, Duration window);
}
