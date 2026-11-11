package io.vitalstream.ingest.adapters.inbound;

/** Request-size limits shared by every inbound format. */
final class PayloadLimits {

  /** CONTRACTS section 4: at most 500 readings per request. */
  static final int MAX_READINGS = 500;

  private PayloadLimits() {}

  static void requireWithinLimit(int count) {
    if (count > MAX_READINGS) {
      throw new MalformedPayloadException(
          "A request may carry at most " + MAX_READINGS + " readings, got " + count);
    }
  }
}
