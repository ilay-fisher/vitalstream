package io.vitalstream.clinical.application;

import io.micrometer.core.instrument.MeterRegistry;
import io.vitalstream.clinical.domain.Alert;
import io.vitalstream.clinical.domain.AlertType;
import io.vitalstream.clinical.domain.DeliveryPlanner;
import io.vitalstream.clinical.domain.EpisodeChange;
import io.vitalstream.clinical.domain.OutboxEventType;
import io.vitalstream.clinical.domain.SuppressionPolicy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Closes episodes that have been silent for {@code ALERT_RESOLVE_AFTER} (CONTRACTS section 3.4).
 * Clinicians never resolve manually: an episode ends when the condition stops being detected.
 *
 * <p>Safe on any number of replicas and safe to repeat: the resolution is one conditional statement
 * in the store, so a second runner finds nothing left to resolve and a detection that lands
 * concurrently either keeps the episode alive or opens a new one after it.
 */
@Service
public class EpisodeResolver {

  private static final Logger log = LoggerFactory.getLogger(EpisodeResolver.class);

  private final AlertRepository alerts;
  private final SuppressionStore suppression;
  private final UnitOfWork unitOfWork;
  private final EpisodeEffects effects;
  private final Clock clock;
  private final MeterRegistry meters;
  private final Duration resolveAfter;

  public EpisodeResolver(
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
    this.clock = clock;
    this.meters = meters;
    this.resolveAfter = resolveAfter;
  }

  /**
   * Resolves every episode last seen before {@code now - resolveAfter}. Children of a resolved
   * parent become top-level again, and a parent that lost a resolved child is republished with its
   * new child count; every changed episode is re-indexed and pushed to the live feed.
   *
   * @return number of episodes resolved by this call
   */
  public int resolveStale() {
    Instant now = clock.instant();
    String correlationId = MDC.get(Correlation.MDC_KEY);
    Resolution resolution = unitOfWork.run(() -> resolve(now, correlationId));
    effects.publishLive(resolution.views());
    for (ResolvedEpisode episode : resolution.resolved()) {
      meters
          .counter("vitalstream_alert_episodes", "event", "resolved", "type", episode.type().name())
          .increment();
      log.atInfo().addKeyValue("alertId", episode.id()).log("Episode {} resolved", episode.id());
    }
    for (AlertType type : resolution.unlinked()) {
      meters
          .counter("vitalstream_alert_episodes", "event", "unlinked", "type", type.name())
          .increment();
    }
    return resolution.resolved().size();
  }

  private Resolution resolve(Instant now, String correlationId) {
    List<ResolvedEpisode> resolved = alerts.resolveStale(now.minus(resolveAfter), now);
    if (resolved.isEmpty()) {
      return new Resolution(List.of(), List.of(), List.of());
    }
    Instant staleBefore = now.minus(resolveAfter);
    Set<UUID> resolvedIds = new LinkedHashSet<>();
    resolved.forEach(r -> resolvedIds.add(r.id()));

    List<UUID> unlinked = alerts.unlinkChildren(resolvedIds);
    Set<UUID> changed = new LinkedHashSet<>(resolvedIds);
    changed.addAll(unlinked);
    for (ResolvedEpisode episode : resolved) {
      UUID parent = episode.parentAlertId();
      if (parent != null && !resolvedIds.contains(parent)) {
        alerts.touch(parent);
        changed.add(parent);
      }
    }

    List<AlertView> views = new ArrayList<>();
    List<AlertType> unlinkedTypes = new ArrayList<>();
    for (UUID id : changed) {
      Alert stored = alerts.findById(id).orElseThrow();
      AlertView view = AlertView.from(stored);
      boolean wasUnlinked = unlinked.contains(id);
      EpisodeChange change = wasUnlinked ? EpisodeChange.UNLINKED : EpisodeChange.UPDATED;
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
        alerts.setNotifyPending(id, pending);
      }
      if (wasUnlinked) {
        unlinkedTypes.add(stored.type());
      }
      views.add(view);
    }
    return new Resolution(resolved, views, unlinkedTypes);
  }

  /** Claims the shared suppression window, owned by the episode so it pages at most once. */
  private boolean claimNotificationWindow(Alert episode) {
    return suppression.tryAcquire(
        SuppressionPolicy.keyFor(episode), episode.id().toString(), SuppressionPolicy.WINDOW);
  }

  private record Resolution(
      List<ResolvedEpisode> resolved, List<AlertView> views, List<AlertType> unlinked) {}
}
