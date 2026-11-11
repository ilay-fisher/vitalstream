package io.vitalstream.clinical.application;

import io.vitalstream.clinical.domain.OutboxEventType;
import java.time.Clock;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

/**
 * Drains the transactional outbox.
 *
 * <p>Rows are claimed with {@code FOR UPDATE SKIP LOCKED}, so any number of replicas can run the
 * relay concurrently without double delivery or blocking each other. A failing row stays
 * unpublished and is retried on the next tick without holding up the rest of the batch. Delivery is
 * therefore at-least-once; every sink is idempotent or tolerates duplicates.
 */
@Service
public class OutboxRelay {

  private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

  private final OutboxRepository outbox;
  private final OutboxPayloads payloads;
  private final UnitOfWork unitOfWork;
  private final Clock clock;
  private final Map<OutboxEventType, OutboxSink> sinks = new EnumMap<>(OutboxEventType.class);

  public OutboxRelay(
      OutboxRepository outbox,
      OutboxPayloads payloads,
      UnitOfWork unitOfWork,
      Clock clock,
      List<OutboxSink> sinkBeans) {
    this.outbox = outbox;
    this.payloads = payloads;
    this.unitOfWork = unitOfWork;
    this.clock = clock;
    sinkBeans.forEach(s -> sinks.put(s.handles(), s));
  }

  /**
   * Delivers one batch.
   *
   * @return number of rows delivered and marked published
   */
  public int relayBatch(int batchSize) {
    return unitOfWork.run(() -> {
      int delivered = 0;
      for (OutboxEntry entry : outbox.lockUnpublished(batchSize)) {
        if (deliver(entry)) {
          outbox.markPublished(entry.id(), clock.instant());
          delivered++;
        }
      }
      return delivered;
    });
  }

  private boolean deliver(OutboxEntry entry) {
    try {
      OutboxPayloads.Envelope envelope = payloads.read(entry.payload());
      if (envelope.correlationId() != null) {
        MDC.put(Correlation.MDC_KEY, envelope.correlationId());
      }
      OutboxSink sink = sinks.get(entry.eventType());
      if (sink == null) {
        throw new IllegalStateException("No sink for " + entry.eventType());
      }
      sink.deliver(
          new OutboxMessage(entry.eventType(), envelope.correlationId(), envelope.alert()));
      log.info("Outbox {} delivered for alert {}", entry.eventType(), entry.aggregateId());
      return true;
    } catch (Exception e) {
      log.warn(
          "Outbox {} for alert {} failed, will retry: {}",
          entry.eventType(),
          entry.aggregateId(),
          e.toString());
      return false;
    } finally {
      MDC.remove(Correlation.MDC_KEY);
    }
  }
}
