package io.vitalstream.ingest.domain.port;

/**
 * Result of a rate-limit check.
 *
 * @param allowed whether the request may proceed
 * @param retryAfterSeconds when denied, whole seconds (at least 1) after which a token is available
 */
public record RateLimitDecision(boolean allowed, long retryAfterSeconds) {

  public static RateLimitDecision permit() {
    return new RateLimitDecision(true, 0);
  }

  public static RateLimitDecision deny(long retryAfterSeconds) {
    return new RateLimitDecision(false, Math.max(1, retryAfterSeconds));
  }
}
