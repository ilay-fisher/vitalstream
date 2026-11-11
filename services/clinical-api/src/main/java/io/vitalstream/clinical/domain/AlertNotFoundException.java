package io.vitalstream.clinical.domain;

import java.util.UUID;

/** Raised when an alert id does not exist. */
public class AlertNotFoundException extends RuntimeException {

  public AlertNotFoundException(UUID id) {
    super("Alert " + id + " not found");
  }
}
