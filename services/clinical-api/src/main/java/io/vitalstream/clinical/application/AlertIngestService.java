package io.vitalstream.clinical.application;

import io.micrometer.core.instrument.MeterRegistry;
import io.vitalstream.clinical.domain.Absorption;
import io.vitalstream.clinical.domain.Alert;
import io.vitalstream.clinical.domain.AlertType;
import io.vitalstream.clinical.domain.ConcurrentAlertUpdateException;
import io.vitalstream.clinical.domain.DeliveryPlanner;
import io.vitalstream.clinical.domain.Detection;
import io.vitalstream.clinical.domain.EpisodeChange;
import io.vitalstream.clinical.domain.EpisodeHierarchy;
import io.vitalstream.clinical.domain.OutboxEventType;
import io.vitalstream.clinical.domain.SuppressionPolicy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Folds stream-processor detections into alert episodes (CONTRACTS section 3.4) and queues the
 * resulting side effects.
 *
 * <p>The episode rows and their outbox rows are written in one transaction, so a crash can never
 * leave a change without its notifications (or notifications without the change). Replays and late
 * detections change nothing. Kafka keys by patient, so one patient is handled by one consumer at a
 * time; the version compare-and-set and the partial unique index still guard against the
 * acknowledge endpoint, the resolver and other replicas, and a lost race is retried against fresh
 * state.
 */
@Service
public class AlertIngestService {

  /** What happened to an inbound detection. */
  public enum Outcome {
    OPENED,
    UPDATED,
    ESCALATED,
    /** Late, replayed or otherwise without effect. */
    IGNORED
  }

  private static final Logger log = LoggerFactory.getLogger(AlertIngestService.class);
  private static final int MAX_ATTEMPTS = 3;

  private final AlertRepository alerts;
  private final SuppressionStore suppression;
  private final UnitOfWork unitOfWork;
  private final EpisodeEffects effects;
  private final MeterRegistry meters;
  private final Clock clock;
  private final Duration resolveAfter;

  public AlertIngestService(
      AlertRepository alerts,
      OutboxRepository outbox,
      SuppressionStore suppression,
      LiveFeedPublisher liveFeed,
      UnitOfWork unitOfWork,
      OutboxPayloads payloads,
      Clock clock,
      MeterRegistry meters,
      @Value("${vitalstream.episodes.resolve-after:PT5M}") Duration resolveAfter) {
    this.alerts = alerts;
    this.suppression = suppression;
    this.unitOfWork = unitOfWork;
    this.effects = new EpisodeEffects(outbox, liveFeed, payloads, clock);
    this.meters = meters;
    this.clock = clock;
    this.resolveAfter = resolveAfter;
  }

  /** Applies the detection and queues its side effects; the live feed is notified after commit. */
  public Outcome ingest(AlertEvent event, String correlationId) {
    Detection detection = event.toDetection();
    Applied applied = applyWithRetry(detection, correlationId);
    effects.publishLive(applied.views());
    record(detection, applied);
    return applied.outcome();
  }

  private Applied applyWithRetry(Detection detection, String correlationId) {
    for (int attempt = 1; ; attempt++) {
      try {
        return unitOfWork.run(() -> apply(detection, correlationId));
      } catch (ConcurrentAlertUpdateException e) {
        if (attempt >= MAX_ATTEMPTS) {
          throw e;
        }
        log.warn(
            "Episode {} changed concurrently, retrying ({}/{})",
            e.alertId(),
            attempt,
            MAX_ATTEMPTS);
      }
    }
  }

  private Applied apply(Detection d, String correlationId) {
    Optional<Alert> active = alerts.findActive(d.patientId(), d.type(), d.metric());
    Optional<Alert> news2 =
        d.type() == AlertType.NEWS2
            ? Optional.empty()
            : alerts.findActive(d.patientId(), AlertType.NEWS2, null);

    Alert before = active.orElse(null);
    Alert written;
    EpisodeChange change;
    if (before == null) {
      Alert opened = Alert.open(d);
      opened = opened.withParent(EpisodeHierarchy.parentFor(opened, news2));
      if (!alerts.insert(opened)) {
        return refusedInsert(opened, d);
      }
      written = opened;
      change = EpisodeChange.OPENED;
    } else {
      Optional<Absorption> absorbed = before.absorb(d);
      if (absorbed.isEmpty()) {
        return Applied.ignored(before.id());
      }
      Alert next = absorbed.get().episode();
      next = next.withParent(EpisodeHierarchy.parentFor(next, news2));
      if (!alerts.update(next, before.version())) {
        throw new ConcurrentAlertUpdateException(before.id());
      }
      written = next;
      change = absorbed.get().change();
    }
    return afterWrite(d, before, written, change, correlationId);
  }

  /**
   * Nothing was inserted: if the key now has an episode a concurrent writer won, so retry as an
   * update; otherwise the episode id already exists as a resolved episode and this is a replay of
   * its first detection.
   */
  private Applied refusedInsert(Alert opened, Detection d) {
    if (alerts.findActive(d.patientId(), d.type(), d.metric()).isPresent()) {
      throw new ConcurrentAlertUpdateException(opened.id());
    }
    return Applied.ignored(opened.id());
  }

  private Applied afterWrite(
      Detection d, Alert before, Alert written, EpisodeChange change, String correlationId) {
    Set<UUID> touchedParents = parentsWhoseChildCountChanged(before, written);
    touchedParents.forEach(alerts::touch);
    Set<UUID> related = new LinkedHashSet<>(touchedParents);
    if (written.type() == AlertType.NEWS2 && change.mayNotify()) {
      related.addAll(alerts.linkChildren(d.patientId(), written.id(), written.severity()));
    }
    related.remove(written.id());

    Alert stored = alerts.findById(written.id()).orElseThrow();
    AlertView view = AlertView.from(stored);
    Instant staleBefore = clock.instant().minus(resolveAfter);
    if (DeliveryPlanner.skippedAsStale(stored, change, staleBefore)) {
      StaleNotifications.skipped(meters, stored);
    }
    boolean suppressed =
        DeliveryPlanner.notifies(stored, change, staleBefore) && !claimNotificationWindow(stored);
    for (OutboxEventType type : DeliveryPlanner.plan(stored, change, suppressed, staleBefore)) {
      effects.enqueue(type, view, correlationId);
    }
    boolean pending = DeliveryPlanner.nextNotifyPending(stored, change, staleBefore);
    if (pending != stored.notifyPending()) {
      alerts.setNotifyPending(stored.id(), pending);
    }

    List<AlertView> views = new ArrayList<>(List.of(view));
    for (UUID id : related) {
      AlertView relatedView = AlertView.from(alerts.findById(id).orElseThrow());
      effects.enqueue(OutboxEventType.ALERT_INDEX, relatedView, correlationId);
      views.add(relatedView);
    }
    return new Applied(outcomeOf(change), written.id(), written.occurrences(), suppressed, views);
  }

  private static Set<UUID> parentsWhoseChildCountChanged(Alert before, Alert written) {
    Set<UUID> parents = new LinkedHashSet<>();
    UUID previous = before == null ? null : before.parentAlertId();
    if (!Objects.equals(previous, written.parentAlertId())) {
      if (previous != null) {
        parents.add(previous);
      }
      if (written.parentAlertId() != null) {
        parents.add(written.parentAlertId());
      }
    }
    return parents;
  }

  /**
   * Claims the shared suppression window. The episode id owns it, so an escalation of an episode
   * that already paged is never swallowed by its own opening notification.
   */
  private boolean claimNotificationWindow(Alert episode) {
    return suppression.tryAcquire(
        SuppressionPolicy.keyFor(episode), episode.id().toString(), SuppressionPolicy.WINDOW);
  }

  private static Outcome outcomeOf(EpisodeChange change) {
    return switch (change) {
      case OPENED -> Outcome.OPENED;
      case UPDATED -> Outcome.UPDATED;
      case ESCALATED -> Outcome.ESCALATED;
      case UNLINKED -> throw new IllegalStateException("a detection never unlinks an episode");
    };
  }

  private void record(Detection d, Applied applied) {
    String outcome = applied.outcome().name().toLowerCase(Locale.ROOT);
    meters.counter("alerts.consumed", "outcome", outcome).increment();
    if (applied.suppressed()) {
      meters.counter("alerts.suppressed").increment();
    }
    if (applied.outcome() != Outcome.IGNORED) {
      meters
          .counter(
              "vitalstream_alerts_raised", "severity", d.severity().name(), "type", d.type().name())
          .increment();
    }
    if (applied.outcome() == Outcome.OPENED || applied.outcome() == Outcome.ESCALATED) {
      meters
          .counter("vitalstream_alert_episodes", "event", outcome, "type", d.type().name())
          .increment();
    }
    log.atInfo()
        .addKeyValue("alertId", applied.episodeId())
        .addKeyValue("occurrences", applied.occurrences())
        .log(
            "Episode {} for patient {}: {}",
            applied.episodeId(),
            PatientRef.of(d.patientId()),
            outcome);
  }

  private record Applied(
      Outcome outcome, UUID episodeId, int occurrences, boolean suppressed, List<AlertView> views) {

    static Applied ignored(UUID episodeId) {
      return new Applied(Outcome.IGNORED, episodeId, 0, false, List.of());
    }
  }
}
