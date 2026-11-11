package io.vitalstream.ingest.domain.port;

import io.vitalstream.ingest.domain.model.VitalReading;
import java.util.concurrent.CompletableFuture;

/** Outbound port to the event log. */
public interface EventPublisher {

  /**
   * Publishes one reading. The future completes only when the broker has acknowledged the write and
   * fails otherwise, which lets callers answer 202 strictly after durability.
   */
  CompletableFuture<Void> publish(VitalReading reading);
}
