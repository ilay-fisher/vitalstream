package io.vitalstream.clinical.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Port: transactional outbox storage. */
public interface OutboxRepository {

  void add(OutboxEntry entry);

  /**
   * Locks up to {@code limit} unpublished rows, oldest first, skipping rows another relay instance
   * already holds so that replicas never deliver the same row twice. Must run inside a transaction
   * that stays open until the rows are marked published.
   */
  List<OutboxEntry> lockUnpublished(int limit);

  void markPublished(UUID id, Instant at);

  /** Age in seconds of the oldest unpublished row, 0 when the outbox is drained. */
  double oldestUnpublishedAgeSeconds();
}
