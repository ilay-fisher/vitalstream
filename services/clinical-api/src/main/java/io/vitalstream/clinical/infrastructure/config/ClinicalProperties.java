package io.vitalstream.clinical.infrastructure.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Service-specific settings; infrastructure endpoints come from the shared env vars. */
@ConfigurationProperties("vitalstream")
public record ClinicalProperties(
    @DefaultValue Outbox outbox,
    @DefaultValue Kafka kafka,
    @DefaultValue Rabbit rabbit,
    @DefaultValue Sqs sqs,
    @DefaultValue Elasticsearch elasticsearch,
    @DefaultValue Cassandra cassandra,
    @DefaultValue Cors cors) {

  /** Outbox relay batch size (the polling interval is {@code vitalstream.outbox.interval-ms}). */
  public record Outbox(@DefaultValue("50") int batchSize) {}

  /** Topics and the retry policy before a record goes to the dead-letter topic. */
  public record Kafka(
      @DefaultValue("alerts.detected") String alertsTopic,
      @DefaultValue("alerts.detected.dlq") String dlqTopic,
      @DefaultValue("3") int retries,
      @DefaultValue("500ms") Duration backoffInitial,
      @DefaultValue("2.0") double backoffMultiplier,
      @DefaultValue("10s") Duration backoffMax) {}

  /** Notification exchange and how long to wait for the broker's publisher confirm. */
  public record Rabbit(
      @DefaultValue("alerts.notify") String exchange,
      @DefaultValue("5s") Duration confirmTimeout) {}

  /** Escalation queue and AWS endpoint (blank endpoint means the real AWS). */
  public record Sqs(
      @DefaultValue("alert-escalations") String queueName,
      @DefaultValue("") String endpoint,
      @DefaultValue("us-east-1") String region) {}

  /** Search index endpoint. */
  public record Elasticsearch(
      @DefaultValue("http://localhost:9200") String url, @DefaultValue("alerts") String index) {}

  /** Time-series store: comma separated {@code host:port} contact points. */
  public record Cassandra(
      @DefaultValue("localhost:9042") String contactPoints,
      @DefaultValue("datacenter1") String datacenter,
      @DefaultValue("vitalstream") String keyspace) {}

  /** Browser origins allowed to call the API (dashboard in prod build and Vite dev). */
  public record Cors(
      @DefaultValue({"http://localhost:3000", "http://localhost:5173"}) String[] allowedOrigins) {}
}
