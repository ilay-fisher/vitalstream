package io.vitalstream.clinical.domain;

import java.util.Set;

/** Lifecycle of an alert. Transitions only move forward so a clinician action is never undone. */
public enum AlertStatus {
  OPEN,
  ACKNOWLEDGED,
  RESOLVED;

  /** Whether moving from this status to {@code target} is a legal forward transition. */
  public boolean canTransitionTo(AlertStatus target) {
    return allowedTargets().contains(target);
  }

  private Set<AlertStatus> allowedTargets() {
    return switch (this) {
      case OPEN -> Set.of(ACKNOWLEDGED, RESOLVED);
      case ACKNOWLEDGED -> Set.of(RESOLVED);
      case RESOLVED -> Set.of();
    };
  }
}
