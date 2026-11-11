package io.vitalstream.clinical.domain;

import java.util.UUID;

/** Raised when an optimistic-lock check fails because someone else changed the alert first. */
public class ConcurrentAlertUpdateException extends RuntimeException {

  public ConcurrentAlertUpdateException(UUID id) {
    super("Alert " + id + " was modified concurrently, reload and retry");
  }
}
