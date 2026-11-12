package io.vitalstream.clinical.domain;

/** What a detection did to its episode. */
public enum EpisodeChange {
  /** No non-resolved episode existed for the key: a new one was created. */
  OPENED,
  /** The episode absorbed the detection without getting more severe. */
  UPDATED,
  /** The detection raised the episode's severity (and re-opened it if it was acknowledged). */
  ESCALATED;

  /** Whether this change is the kind that may page someone (open or escalation). */
  public boolean mayNotify() {
    return this == OPENED || this == ESCALATED;
  }
}
