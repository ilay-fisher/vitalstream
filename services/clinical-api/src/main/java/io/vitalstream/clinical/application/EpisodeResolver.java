package io.vitalstream.clinical.application;

import io.micrometer.core.instrument.MeterRegistry;
import io.vitalstream.clinical.domain.OutboxEventType;
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
  private final UnitOfWork unitOfWork;
  private final EpisodeEffects effects;
  private final Clock clock;
  private final MeterRegistry meters;
  private final Duration resolveAfter;

  public EpisodeResolver(
      AlertRepository alerts,
      OutboxRepository outbox,
      LiveFeedPublisher liveFeed,
      UnitOfWork unitOfWork,
      OutboxPayloads payloads,
      Clock clock,
      MeterRegistry meters,
      @Value("${vitalstream.episodes.resolve-after:PT5M}") Duration resolveAfter) {
    this.alerts = alerts;
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
    return resolution.resolved().size();
  }

  private Resolution resolve(Instant now, String correlationId) {
    List<ResolvedEpisode> resolved = alerts.resolveStale(now.minus(resolveAfter), now);
    if (resolved.isEmpty()) {
      return new Resolution(List.of(), List.of());
    }
    Set<UUID> resolvedIds = new LinkedHashSet<>();
    resolved.forEach(r -> resolvedIds.add(r.id()));

    Set<UUID> changed = new LinkedHashSet<>(resolvedIds);
    changed.addAll(alerts.unlinkChildren(resolvedIds));
    for (ResolvedEpisode episode : resolved) {
      UUID parent = episode.parentAlertId();
      if (parent != null && !resolvedIds.contains(parent)) {
        alerts.touch(parent);
        changed.add(parent);
      }
    }

    List<AlertView> views = new ArrayList<>();
    for (UUID id : changed) {
      AlertView view = AlertView.from(alerts.findById(id).orElseThrow());
      effects.enqueue(OutboxEventType.ALERT_INDEX, view, correlationId);
      views.add(view);
    }
    return new Resolution(resolved, views);
  }

  private record Resolution(List<ResolvedEpisode> resolved, List<AlertView> views) {}
}
