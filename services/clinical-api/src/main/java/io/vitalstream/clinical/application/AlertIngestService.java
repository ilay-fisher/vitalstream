package io.vitalstream.clinical.application;

import io.micrometer.core.instrument.MeterRegistry;
import io.vitalstream.clinical.domain.Alert;
import io.vitalstream.clinical.domain.DeliveryPlanner;
import io.vitalstream.clinical.domain.OutboxEventType;
import io.vitalstream.clinical.domain.SuppressionPolicy;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Turns a detected alert into stored state plus pending side effects.
 *
 * <p>The alert row and its outbox rows are written in one transaction, so a crash can never leave
 * an alert without its notifications (or notifications without an alert). Redelivered events hit
 * the primary key and change nothing.
 */
@Service
public class AlertIngestService {

  /** What happened to an inbound event. */
  public enum Outcome {
    PERSISTED,
    DUPLICATE
  }

  private static final Logger log = LoggerFactory.getLogger(AlertIngestService.class);

  private final AlertRepository alerts;
  private final OutboxRepository outbox;
  private final SuppressionStore suppression;
  private final LiveFeedPublisher liveFeed;
  private final UnitOfWork unitOfWork;
  private final OutboxPayloads payloads;
  private final Clock clock;
  private final MeterRegistry meters;

  public AlertIngestService(
      AlertRepository alerts,
      OutboxRepository outbox,
      SuppressionStore suppression,
      LiveFeedPublisher liveFeed,
      UnitOfWork unitOfWork,
      OutboxPayloads payloads,
      Clock clock,
      MeterRegistry meters) {
    this.alerts = alerts;
    this.outbox = outbox;
    this.suppression = suppression;
    this.liveFeed = liveFeed;
    this.unitOfWork = unitOfWork;
    this.payloads = payloads;
    this.clock = clock;
    this.meters = meters;
  }

  /** Stores the alert and queues its side effects; the live feed is notified after commit. */
  public Outcome ingest(AlertEvent event, String correlationId) {
    Optional<AlertView> stored = unitOfWork.run(() -> store(event.toAlert(), correlationId));
    stored.ifPresent(this::publishLive);
    Outcome outcome = stored.isPresent() ? Outcome.PERSISTED : Outcome.DUPLICATE;
    meters.counter("alerts.consumed", "outcome", outcome.name().toLowerCase()).increment();
    log.info("Alert {} for patient {}: {}", event.alertId(), event.patientId(), outcome);
    return outcome;
  }

  private Optional<AlertView> store(Alert alert, String correlationId) {
    if (!alerts.insertIfAbsent(alert)) {
      return Optional.empty();
    }
    boolean suppressed =
        !suppression.tryAcquire(
            SuppressionPolicy.keyFor(alert), alert.id().toString(), SuppressionPolicy.WINDOW);
    if (suppressed) {
      meters.counter("alerts.suppressed").increment();
      log.info("Notifications suppressed for alert {}", alert.id());
    }
    AlertView view = AlertView.from(alert);
    String payload = payloads.write(correlationId, view);
    for (OutboxEventType type : DeliveryPlanner.plan(alert, suppressed)) {
      outbox.add(new OutboxEntry(UUID.randomUUID(), alert.id(), type, payload, clock.instant()));
    }
    return Optional.of(view);
  }

  private void publishLive(AlertView view) {
    try {
      liveFeed.publish(view);
    } catch (RuntimeException e) {
      log.warn("Live feed publish failed for alert {}: {}", view.alertId(), e.toString());
    }
  }
}
