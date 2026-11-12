package io.vitalstream.clinical.infrastructure;

import io.vitalstream.clinical.application.Correlation;
import io.vitalstream.clinical.application.EpisodeResolver;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs the episode resolver every 30 s on every replica. No leader election is needed: resolution
 * is a single conditional UPDATE, so concurrent runs cannot double-resolve.
 */
@Component
public class EpisodeResolverScheduler {

  private static final Logger log = LoggerFactory.getLogger(EpisodeResolverScheduler.class);

  private final EpisodeResolver resolver;

  public EpisodeResolverScheduler(EpisodeResolver resolver) {
    this.resolver = resolver;
  }

  @Scheduled(
      fixedDelayString = "${vitalstream.episodes.resolve-interval-ms:30000}",
      initialDelayString = "${vitalstream.episodes.resolve-interval-ms:30000}")
  void tick() {
    MDC.put(Correlation.MDC_KEY, UUID.randomUUID().toString());
    try {
      resolver.resolveStale();
    } catch (RuntimeException e) {
      log.warn("Episode resolver tick failed, will retry: {}", e.toString());
    } finally {
      MDC.remove(Correlation.MDC_KEY);
    }
  }
}
