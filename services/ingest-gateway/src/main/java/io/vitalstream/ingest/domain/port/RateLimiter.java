package io.vitalstream.ingest.domain.port;

/** Per-device request throttle so one misbehaving monitor cannot starve the others. */
public interface RateLimiter {

  /** Consumes one token for the device, or reports how long to wait. */
  RateLimitDecision tryAcquire(String deviceId);
}
