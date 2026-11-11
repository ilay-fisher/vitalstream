package io.vitalstream.clinical.infrastructure.kafka;

/** A record that can never be processed, no matter how often it is retried. */
public class PoisonMessageException extends RuntimeException {

  public PoisonMessageException(String message, Throwable cause) {
    super(message, cause);
  }

  public PoisonMessageException(String message) {
    super(message);
  }
}
