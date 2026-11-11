package io.vitalstream.clinical.infrastructure.jdbc;

import io.vitalstream.clinical.application.OutboxEntry;
import io.vitalstream.clinical.application.OutboxRepository;
import io.vitalstream.clinical.domain.OutboxEventType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Postgres implementation of the transactional outbox. */
@Repository
public class JdbcOutboxRepository implements OutboxRepository {

  private final JdbcClient jdbc;

  public JdbcOutboxRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void add(OutboxEntry entry) {
    jdbc.sql(
            """
            INSERT INTO clinical.outbox (id, aggregate_id, event_type, payload, created_at)
            VALUES (:id, :aggregateId, :eventType, CAST(:payload AS jsonb), :createdAt)
            """)
        .param("id", entry.id())
        .param("aggregateId", entry.aggregateId().toString())
        .param("eventType", entry.eventType().name())
        .param("payload", entry.payload())
        .param("createdAt", Timestamps.toDb(entry.createdAt()))
        .update();
  }

  @Override
  public List<OutboxEntry> lockUnpublished(int limit) {
    return jdbc.sql(
            """
            SELECT id, aggregate_id, event_type, payload::text AS payload, created_at
              FROM clinical.outbox
             WHERE published_at IS NULL
             ORDER BY created_at, id
             LIMIT :limit
               FOR UPDATE SKIP LOCKED
            """)
        .param("limit", limit)
        .query((rs, row) ->
            new OutboxEntry(
                rs.getObject("id", UUID.class),
                UUID.fromString(rs.getString("aggregate_id")),
                OutboxEventType.valueOf(rs.getString("event_type")),
                rs.getString("payload"),
                Timestamps.read(rs, "created_at")))
        .list();
  }

  @Override
  public void markPublished(UUID id, Instant at) {
    jdbc.sql("UPDATE clinical.outbox SET published_at = :at WHERE id = :id")
        .param("at", Timestamps.toDb(at))
        .param("id", id)
        .update();
  }

  @Override
  public double oldestUnpublishedAgeSeconds() {
    return jdbc.sql(
            """
            SELECT COALESCE(EXTRACT(EPOCH FROM (now() - min(created_at))), 0)::float8
              FROM clinical.outbox
             WHERE published_at IS NULL
            """)
        .query(Double.class)
        .single();
  }
}
