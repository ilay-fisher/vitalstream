package io.vitalstream.clinical.domain;

/** What a detection did to its episode. */
public enum EpisodeChange {
  /** No non-resolved episode existed for the key: a new one was created. */
  OPENED,
  /** The episode absorbed the detection without getting more severe. */
  UPDATED,
  /** The detection raised the episode's severity (and re-opened it if it was acknowledged). */
  ESCALATED,
  /**
   * The episode lost its NEWS2 parent because the parent resolved while the condition persists: it
   * is top-level now, which counts as an open for notification purposes.
   */
  UNLINKED;

  /** Whether this change is the kind that may page someone (open, escalation or unlink). */
  public boolean mayNotify() {
    return this != UPDATED;
  }
}
