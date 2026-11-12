package io.vitalstream.clinical.application;

import io.micrometer.core.instrument.MeterRegistry;
import io.vitalstream.clinical.domain.Alert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Records a notification dropped by the staleness rule (CONTRACTS section 3.4): the condition went
 * quiet before anyone could be paged, so paging would only be noise. Counted and logged so the
 * silence is explainable; the log carries the pseudonymous {@code patientRef} only.
 */
final class StaleNotifications {

  private static final Logger log = LoggerFactory.getLogger(StaleNotifications.class);

  private StaleNotifications() {}

  static void skipped(MeterRegistry meters, Alert episode) {
    meters.counter("vitalstream.alert.notifications.skipped", "reason", "stale").increment();
    log.atInfo()
        .addKeyValue("alertId", episode.id())
        .addKeyValue("patientRef", PatientRef.of(episode.patientId()))
        .log(
            "Notification for episode {} of patient {} skipped: stale",
            episode.id(),
            PatientRef.of(episode.patientId()));
  }
}
