package io.vitalstream.clinical.application;

import io.vitalstream.clinical.domain.Alert;
import io.vitalstream.clinical.domain.AlertNotFoundException;
import io.vitalstream.clinical.domain.ConcurrentAlertUpdateException;
import io.vitalstream.clinical.domain.OutboxEventType;
import java.time.Clock;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

/** Clinician acknowledgement with optimistic locking. */
@Service
public class AlertAckService {

  private static final Logger log = LoggerFactory.getLogger(AlertAckService.class);

  private final AlertRepository alerts;
  private final OutboxRepository outbox;
  private final LiveFeedPublisher liveFeed;
  private final UnitOfWork unitOfWork;
  private final OutboxPayloads payloads;
  private final Clock clock;

  public AlertAckService(
      AlertRepository alerts,
      OutboxRepository outbox,
      LiveFeedPublisher liveFeed,
      UnitOfWork unitOfWork,
      OutboxPayloads payloads,
      Clock clock) {
    this.alerts = alerts;
    this.outbox = outbox;
    this.liveFeed = liveFeed;
    this.unitOfWork = unitOfWork;
    this.payloads = payloads;
    this.clock = clock;
  }

  /**
   * Acknowledges an OPEN alert.
   *
   * @throws AlertNotFoundException unknown id
   * @throws io.vitalstream.clinical.domain.InvalidStatusTransitionException already acknowledged
   * @throws ConcurrentAlertUpdateException another writer changed the alert in between
   */
  public AlertView acknowledge(UUID id, String by, String note) {
    AlertView view = unitOfWork.run(() -> applyAcknowledgement(id, by, note));
    try {
      liveFeed.publish(view);
    } catch (RuntimeException e) {
      log.warn("Live feed publish failed for alert {}: {}", id, e.toString());
    }
    log.info("Alert {} acknowledged by {}", id, by);
    return view;
  }

  private AlertView applyAcknowledgement(UUID id, String by, String note) {
    Alert current = alerts.findById(id).orElseThrow(() -> new AlertNotFoundException(id));
    Alert acknowledged = current.acknowledge(by, note, clock.instant());
    if (!alerts.updateAcknowledgement(acknowledged, current.version())) {
      throw new ConcurrentAlertUpdateException(id);
    }
    AlertView view = AlertView.from(acknowledged.withVersion(current.version() + 1));
    outbox.add(
        new OutboxEntry(
            UUID.randomUUID(),
            id,
            OutboxEventType.ALERT_INDEX,
            payloads.write(MDC.get(Correlation.MDC_KEY), view),
            clock.instant()));
    return view;
  }
}
