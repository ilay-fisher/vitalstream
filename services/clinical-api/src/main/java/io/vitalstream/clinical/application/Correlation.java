package io.vitalstream.clinical.application;

/** Names under which the correlation id travels (HTTP, Kafka, RabbitMQ, logs). */
public final class Correlation {

  public static final String HTTP_HEADER = "X-Correlation-Id";
  public static final String MESSAGE_HEADER = "correlationId";
  public static final String MDC_KEY = "correlationId";

  private Correlation() {}
}
