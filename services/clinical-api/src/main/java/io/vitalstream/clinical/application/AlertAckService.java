package io.vitalstream.clinical.application;

import io.vitalstream.clinical.domain.Alert;
import io.vitalstream.clinical.domain.AlertNotFoundException;
import io.vitalstream.clinical.domain.ConcurrentAlertUpdateException;
import io.vitalstream.clinical.domain.OutboxEventType;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

/**
 * Clinician acknowledgement with optimistic locking and an audit line per success.
 *
 * <p>Ingest keeps updating an episode while it is acknowledged (occurrence counts), which bumps its
 * version underneath a clinician who read it a moment ago. That is not a user error, so a version
 * clash is retried against fresh state; a conflict is only reported when the fresh state really
 * forbids the acknowledgement or the clashes do not stop.
 */
@Service
public class AlertAckService {

  private static final Logger log = LoggerFactory.getLogger(AlertAckService.class);
  private static final int MAX_ATTEMPTS = 3;

  private final AlertRepository alerts;
  private final UnitOfWork unitOfWork;
  private final EpisodeEffects effects;
  private final Clock clock;

  public AlertAckService(
      AlertRepository alerts,
      OutboxRepository outbox,
      LiveFeedPublisher liveFeed,
      UnitOfWork unitOfWork,
      OutboxPayloads payloads,
      Clock clock) {
    this.alerts = alerts;
    this.unitOfWork = unitOfWork;
    this.effects = new EpisodeEffects(outbox, liveFeed, payloads, clock);
    this.clock = clock;
  }

  /**
   * Acknowledges an OPEN episode as {@code actor} and writes the INFO audit line {@code alert
   * acknowledged} (the correlation id comes from the MDC).
   *
   * @throws AlertNotFoundException unknown id
   * @throws io.vitalstream.clinical.domain.InvalidStatusTransitionException already acknowledged or
   *     resolved
   * @throws ConcurrentAlertUpdateException the episode kept changing underneath all attempts
   */
  public AlertView acknowledge(UUID id, AckActor actor, String note) {
    AlertView view = acknowledgeWithRetry(id, actor.displayName(), note);
    effects.publishLive(List.of(view));
    log.atInfo()
        .addKeyValue("alertId", id)
        .addKeyValue("actor", actor.sub())
        .log("alert acknowledged");
    return view;
  }

  private AlertView acknowledgeWithRetry(UUID id, String by, String note) {
    for (int attempt = 1; ; attempt++) {
      try {
        return unitOfWork.run(() -> applyAcknowledgement(id, by, note));
      } catch (ConcurrentAlertUpdateException e) {
        if (attempt >= MAX_ATTEMPTS) {
          throw e;
        }
        log.debug(
            "Alert {} changed during acknowledgement, retrying ({}/{})", id, attempt, MAX_ATTEMPTS);
      }
    }
  }

  private AlertView applyAcknowledgement(UUID id, String by, String note) {
    Alert current = alerts.findById(id).orElseThrow(() -> new AlertNotFoundException(id));
    Alert acknowledged = current.acknowledge(by, note, clock.instant());
    if (!alerts.updateAcknowledgement(acknowledged, current.version())) {
      throw new ConcurrentAlertUpdateException(id);
    }
    AlertView view = AlertView.from(acknowledged.withVersion(current.version() + 1));
    effects.enqueue(OutboxEventType.ALERT_INDEX, view, MDC.get(Correlation.MDC_KEY));
    return view;
  }
}
