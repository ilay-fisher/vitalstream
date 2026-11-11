package io.vitalstream.clinical.domain;

import java.util.Locale;

/** Clinical urgency of an alert, ordered from least to most urgent. */
public enum Severity {
  LOW,
  MEDIUM,
  HIGH,
  CRITICAL;

  /**
   * RabbitMQ routing key on the {@code alerts.notify} topic exchange. Lowercase so bindings such as
   * {@code alert.critical} and {@code alert.*} stay readable and independent of enum naming.
   */
  public String routingKey() {
    return "alert." + name().toLowerCase(Locale.ROOT);
  }

  /** Only critical alerts leave the notification path and page the on-call team via SQS. */
  public boolean escalates() {
    return this == CRITICAL;
  }
}
