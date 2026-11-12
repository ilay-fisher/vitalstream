package io.vitalstream.clinical.application;

import io.vitalstream.clinical.domain.OutboxEventType;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Side effects every episode change shares: an outbox row per downstream sink, written in the same
 * transaction as the change, and the live-feed publish after the commit.
 */
final class EpisodeEffects {

  private static final Logger log = LoggerFactory.getLogger(EpisodeEffects.class);

  private final OutboxRepository outbox;
  private final LiveFeedPublisher liveFeed;
  private final OutboxPayloads payloads;
  private final Clock clock;

  EpisodeEffects(
      OutboxRepository outbox, LiveFeedPublisher liveFeed, OutboxPayloads payloads, Clock clock) {
    this.outbox = outbox;
    this.liveFeed = liveFeed;
    this.payloads = payloads;
    this.clock = clock;
  }

  /** Queues one delivery of the episode snapshot to {@code type}. */
  void enqueue(OutboxEventType type, AlertView view, String correlationId) {
    outbox.add(
        new OutboxEntry(
            UUID.randomUUID(),
            view.alertId(),
            type,
            payloads.write(correlationId, view),
            clock.instant()));
  }

  /** Best effort: a Redis outage must not fail ingestion, clients can reload from the API. */
  void publishLive(List<AlertView> views) {
    for (AlertView view : views) {
      try {
        liveFeed.publish(view);
      } catch (RuntimeException e) {
        log.warn("Live feed publish failed for alert {}: {}", view.alertId(), e.toString());
      }
    }
  }
}
