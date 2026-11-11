package io.vitalstream.clinical.domain;

/** Raised when a status change would move an alert backwards or repeat a finished step. */
public class InvalidStatusTransitionException extends RuntimeException {

  public InvalidStatusTransitionException(AlertStatus from, AlertStatus to) {
    super("Alert cannot move from " + from + " to " + to);
  }
}
