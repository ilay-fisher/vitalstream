package io.vitalstream.ingest.domain.service;

/** A device exhausted its token bucket; the API layer maps this to 429 with Retry-After. */
public class RateLimitExceededException extends RuntimeException {

  private final String deviceId;
  private final long retryAfterSeconds;

  public RateLimitExceededException(String deviceId, long retryAfterSeconds) {
    super("Rate limit exceeded for device " + deviceId);
    this.deviceId = deviceId;
    this.retryAfterSeconds = retryAfterSeconds;
  }

  public String deviceId() {
    return deviceId;
  }

  public long retryAfterSeconds() {
    return retryAfterSeconds;
  }
}
