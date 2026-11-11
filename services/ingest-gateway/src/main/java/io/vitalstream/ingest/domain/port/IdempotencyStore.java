package io.vitalstream.ingest.domain.port;

import java.util.UUID;

/**
 * Remembers which event ids were already accepted so client retries do not create duplicate Kafka
 * records.
 */
public interface IdempotencyStore {

  /**
   * Atomically claims an event id.
   *
   * @return true if this caller is the first to see the id and must publish it; false if it is a
   *     duplicate
   */
  boolean claim(UUID eventId);

  /**
   * Gives the claim back after a failed publish so the client's retry is not mistaken for a
   * duplicate.
   */
  void release(UUID eventId);
}
