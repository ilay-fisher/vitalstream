package io.vitalstream.clinical.application;

/**
 * Who acknowledges an alert, taken from the verified token and never from the request body. {@code
 * sub} is the stable identity used for auditing, {@code displayName} is what the alert shows.
 */
public record AckActor(String sub, String displayName) {

  /** Display name is the token {@code name} claim when present, else {@code sub}. */
  public static AckActor from(String sub, String nameClaim) {
    boolean hasName = nameClaim != null && !nameClaim.isBlank();
    return new AckActor(sub, hasName ? nameClaim : sub);
  }
}
