package io.vitalstream.clinical.domain;

import java.util.UUID;

/** Raised when an optimistic-lock check fails because someone else changed the alert first. */
public class ConcurrentAlertUpdateException extends RuntimeException {

  private final UUID alertId;

  public ConcurrentAlertUpdateException(UUID id) {
    super("Alert " + id + " was modified concurrently, reload and retry");
    this.alertId = id;
  }

  public UUID alertId() {
    return alertId;
  }
}
