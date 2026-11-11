package io.vitalstream.clinical.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vitalstream.clinical.domain.AlertType;
import io.vitalstream.clinical.domain.OutboxEventType;
import io.vitalstream.clinical.domain.Severity;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

class AlertIngestServiceTest {

  private final AlertRepository alerts = mock(AlertRepository.class);
  private final OutboxRepository outbox = mock(OutboxRepository.class);
  private final SuppressionStore suppression = mock(SuppressionStore.class);
  private final LiveFeedPublisher liveFeed = mock(LiveFeedPublisher.class);
  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  private AlertIngestService service;

  @BeforeEach
  void setUp() {
    service =
        new AlertIngestService(
            alerts,
            outbox,
            suppression,
            liveFeed,
            new DirectUnitOfWork(),
            new OutboxPayloads(JsonMapper.builder().build()),
            Clock.fixed(Instant.parse("2026-11-11T20:20:00Z"), ZoneOffset.UTC),
            meters);
  }

  @Test
  void newCriticalAlertWritesIndexNotifyAndEscalateRowsAndPublishesLive() {
    AlertEvent event = event(Severity.CRITICAL);
    when(alerts.insertIfAbsent(any())).thenReturn(true);
    when(suppression.tryAcquire(any(), any(), any())).thenReturn(true);

    AlertIngestService.Outcome outcome = service.ingest(event, "corr-1");

    assertThat(outcome).isEqualTo(AlertIngestService.Outcome.PERSISTED);
    ArgumentCaptor<OutboxEntry> rows = ArgumentCaptor.forClass(OutboxEntry.class);
    verify(outbox, org.mockito.Mockito.times(3)).add(rows.capture());
    assertThat(rows.getAllValues())
        .extracting(OutboxEntry::eventType)
        .containsExactly(
            OutboxEventType.ALERT_INDEX,
            OutboxEventType.ALERT_NOTIFY,
            OutboxEventType.ALERT_ESCALATE);
    assertThat(rows.getAllValues().getFirst().payload()).contains("corr-1");
    verify(liveFeed).publish(any(AlertView.class));
    assertThat(meters.counter("alerts.consumed", "outcome", "persisted").count()).isEqualTo(1.0);
    assertThat(
            meters
                .counter("vitalstream_alerts_raised", "severity", "CRITICAL", "type", "THRESHOLD")
                .count())
        .isEqualTo(1.0);
  }

  @Test
  void suppressionKeyUsesPatientTypeAndMetricWithFiveMinuteWindow() {
    when(alerts.insertIfAbsent(any())).thenReturn(true);
    when(suppression.tryAcquire(any(), any(), any())).thenReturn(true);
    AlertEvent event = event(Severity.HIGH);

    service.ingest(event, "c");

    verify(suppression)
        .tryAcquire(
            eq("alert:suppress:P-0001:THRESHOLD:HEART_RATE"),
            eq(event.alertId().toString()),
            eq(Duration.ofMinutes(5)));
  }

  @Test
  void suppressedAlertIsStoredAndIndexedButNotNotified() {
    when(alerts.insertIfAbsent(any())).thenReturn(true);
    when(suppression.tryAcquire(any(), any(), any())).thenReturn(false);

    service.ingest(event(Severity.CRITICAL), "c");

    ArgumentCaptor<OutboxEntry> rows = ArgumentCaptor.forClass(OutboxEntry.class);
    verify(outbox).add(rows.capture());
    assertThat(rows.getValue().eventType()).isEqualTo(OutboxEventType.ALERT_INDEX);
    verify(liveFeed).publish(any(AlertView.class));
    assertThat(meters.counter("alerts.suppressed").count()).isEqualTo(1.0);
  }

  @Test
  void replayedAlertChangesNothing() {
    when(alerts.insertIfAbsent(any())).thenReturn(false);

    AlertIngestService.Outcome outcome = service.ingest(event(Severity.CRITICAL), "c");

    assertThat(outcome).isEqualTo(AlertIngestService.Outcome.DUPLICATE);
    verifyNoInteractions(suppression, liveFeed);
    verify(outbox, never()).add(any());
    assertThat(meters.counter("alerts.consumed", "outcome", "duplicate").count()).isEqualTo(1.0);
    assertThat(
            meters
                .counter("vitalstream_alerts_raised", "severity", "CRITICAL", "type", "THRESHOLD")
                .count())
        .isZero();
  }

  @Test
  void liveFeedFailureDoesNotFailIngestion() {
    when(alerts.insertIfAbsent(any())).thenReturn(true);
    when(suppression.tryAcquire(any(), any(), any())).thenReturn(true);
    doThrow(new IllegalStateException("redis down")).when(liveFeed).publish(any());

    assertThat(service.ingest(event(Severity.LOW), "c"))
        .isEqualTo(AlertIngestService.Outcome.PERSISTED);
  }

  static AlertEvent event(Severity severity) {
    return new AlertEvent(
        UUID.randomUUID(),
        "P-0001",
        AlertType.THRESHOLD,
        severity,
        "HEART_RATE",
        142.0,
        null,
        "heart rate high",
        Instant.parse("2026-11-11T20:15:00Z"),
        Instant.parse("2026-11-11T20:16:00Z"),
        Instant.parse("2026-11-11T20:16:02Z"));
  }

  @Test
  void eventCompletenessRequiresIdPatientTypeSeverityAndDetectionTime() {
    assertThat(event(Severity.LOW).isComplete()).isTrue();
    assertThat(
            new AlertEvent(
                    null,
                    "P-1",
                    AlertType.NEWS2,
                    Severity.LOW,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    Instant.now())
                .isComplete())
        .isFalse();
    assertThat(
            new AlertEvent(
                    UUID.randomUUID(),
                    " ",
                    AlertType.NEWS2,
                    Severity.LOW,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    Instant.now())
                .isComplete())
        .isFalse();
  }
}
