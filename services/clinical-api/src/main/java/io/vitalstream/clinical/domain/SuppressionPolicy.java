package io.vitalstream.clinical.domain;

import java.time.Duration;

/**
 * Decides which alerts count as "the same noise". A flapping vital re-raises the same
 * patient/type/metric every minute; clinicians should be paged once per window, not once per
 * detection.
 */
public final class SuppressionPolicy {

  /** How long after a notification further alerts of the same kind are stored but not sent. */
  public static final Duration WINDOW = Duration.ofMinutes(5);

  private static final String NO_METRIC = "-";

  private SuppressionPolicy() {}

  /** Redis key from CONTRACTS section 5: {@code alert:suppress:{patientId}:{type}:{metric}}. */
  public static String keyFor(Alert alert) {
    String metric = alert.metric() == null ? NO_METRIC : alert.metric();
    return "alert:suppress:" + alert.patientId() + ":" + alert.type() + ":" + metric;
  }
}
