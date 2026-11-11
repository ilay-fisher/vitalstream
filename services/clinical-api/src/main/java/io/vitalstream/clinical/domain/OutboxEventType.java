package io.vitalstream.clinical.domain;

/** One outbox row per downstream side effect, so each sink retries independently. */
public enum OutboxEventType {
  /** Upsert the alert document in Elasticsearch. */
  ALERT_INDEX,
  /** Publish to the RabbitMQ {@code alerts.notify} exchange. */
  ALERT_NOTIFY,
  /** Send to the SQS {@code alert-escalations} queue. */
  ALERT_ESCALATE
}
