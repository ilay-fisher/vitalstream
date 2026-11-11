package io.vitalstream.clinical.infrastructure;

import io.vitalstream.clinical.application.OutboxRelay;
import io.vitalstream.clinical.infrastructure.config.ClinicalProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Triggers the outbox relay on a fixed delay; every replica runs it, SKIP LOCKED shares the work.
 */
@Component
public class OutboxScheduler {

  private static final Logger log = LoggerFactory.getLogger(OutboxScheduler.class);

  private final OutboxRelay relay;
  private final int batchSize;

  public OutboxScheduler(OutboxRelay relay, ClinicalProperties properties) {
    this.relay = relay;
    this.batchSize = properties.outbox().batchSize();
  }

  @Scheduled(fixedDelayString = "${vitalstream.outbox.interval-ms:500}")
  void tick() {
    try {
      relay.relayBatch(batchSize);
    } catch (RuntimeException e) {
      log.warn("Outbox relay tick failed, will retry: {}", e.toString());
    }
  }
}
