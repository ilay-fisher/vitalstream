package io.vitalstream.clinical.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vitalstream.clinical.domain.Alert;
import io.vitalstream.clinical.domain.AlertStatus;
import io.vitalstream.clinical.domain.AlertType;
import io.vitalstream.clinical.domain.ConcurrentAlertUpdateException;
import io.vitalstream.clinical.domain.Detection;
import io.vitalstream.clinical.domain.OutboxEventType;
import io.vitalstream.clinical.domain.Severity;
import io.vitalstream.clinical.domain.TestAlerts;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

/** Episode lifecycle on the intake path (CONTRACTS section 3.4), against a behavioural fake. */
class AlertIngestServiceTest {

  private final InMemoryAlertRepository alerts = new InMemoryAlertRepository();
  private final OutboxRepository outbox = mock(OutboxRepository.class);
  private final SuppressionStore suppression = mock(SuppressionStore.class);
  private final LiveFeedPublisher liveFeed = mock(LiveFeedPublisher.class);
  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  private AlertIngestService service;

  @BeforeEach
  void setUp() {
    when(suppression.tryAcquire(any(), any(), any())).thenReturn(true);
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

  private static AlertEvent event(AlertType type, Severity severity, String metric, int minute) {
    return event(TestAlerts.detection(type, severity, metric, minute));
  }

  private static AlertEvent event(Detection d) {
    return new AlertEvent(
        d.id(),
        d.patientId(),
        d.type(),
        d.severity(),
        d.metric(),
        d.value(),
        d.score(),
        d.message(),
        d.windowStart(),
        d.windowEnd(),
        d.detectedAt());
  }

  private static AlertEvent hr(Severity severity, int minute) {
    return event(AlertType.THRESHOLD, severity, "HEART_RATE", minute);
  }

  private static AlertEvent news2(Severity severity, int minute) {
    return event(AlertType.NEWS2, severity, null, minute);
  }

  private List<OutboxEventType> outboxTypesFor(UUID alertId) {
    ArgumentCaptor<OutboxEntry> rows = ArgumentCaptor.forClass(OutboxEntry.class);
    verify(outbox, org.mockito.Mockito.atLeast(0)).add(rows.capture());
    return rows.getAllValues().stream()
        .filter(r -> r.aggregateId().equals(alertId))
        .map(OutboxEntry::eventType)
        .toList();
  }

  private List<AlertView> published() {
    ArgumentCaptor<AlertView> views = ArgumentCaptor.forClass(AlertView.class);
    verify(liveFeed, org.mockito.Mockito.atLeast(0)).publish(views.capture());
    return views.getAllValues();
  }

  // ---- open -------------------------------------------------------------------------------

  @Test
  void firstDetectionOpensAnEpisodeWithIndexNotifyAndEscalateRows() {
    AlertEvent first = event(AlertType.THRESHOLD, Severity.CRITICAL, "SPO2", 0);

    AlertIngestService.Outcome outcome = service.ingest(first, "corr-1");

    assertThat(outcome).isEqualTo(AlertIngestService.Outcome.OPENED);
    Alert episode = alerts.all().getFirst();
    assertThat(alerts.all()).hasSize(1);
    assertThat(episode.status()).isEqualTo(AlertStatus.OPEN);
    assertThat(episode.occurrences()).isEqualTo(1);
    assertThat(episode.lastDetectionId()).isEqualTo(first.alertId());
    assertThat(outboxTypesFor(episode.id()))
        .containsExactly(
            OutboxEventType.ALERT_INDEX,
            OutboxEventType.ALERT_NOTIFY,
            OutboxEventType.ALERT_ESCALATE);
    ArgumentCaptor<OutboxEntry> rows = ArgumentCaptor.forClass(OutboxEntry.class);
    verify(outbox, org.mockito.Mockito.times(3)).add(rows.capture());
    assertThat(rows.getAllValues().getFirst().payload()).contains("corr-1");
    assertThat(published()).extracting(AlertView::alertId).containsExactly(episode.id());
    assertThat(meters.counter("alerts.consumed", "outcome", "opened").count()).isEqualTo(1.0);
    assertThat(
            meters
                .counter("vitalstream_alert_episodes", "event", "opened", "type", "THRESHOLD")
                .count())
        .isEqualTo(1.0);
    assertThat(
            meters
                .counter("vitalstream_alerts_raised", "severity", "CRITICAL", "type", "THRESHOLD")
                .count())
        .isEqualTo(1.0);
  }

  @Test
  void suppressionKeyUsesPatientTypeAndMetricAndTheEpisodeAsOwner() {
    service.ingest(hr(Severity.HIGH, 0), "c");

    Alert episode = alerts.all().getFirst();
    verify(suppression)
        .tryAcquire(
            eq("alert:suppress:P-0001:THRESHOLD:HEART_RATE"),
            eq(episode.id().toString()),
            eq(Duration.ofMinutes(5)));
  }

  @Test
  void suppressedOpeningIsIndexedAndShownLiveButNotNotified() {
    when(suppression.tryAcquire(any(), any(), any())).thenReturn(false);

    service.ingest(hr(Severity.CRITICAL, 0), "c");

    assertThat(outboxTypesFor(alerts.all().getFirst().id()))
        .containsExactly(OutboxEventType.ALERT_INDEX);
    assertThat(published()).hasSize(1);
    assertThat(meters.counter("alerts.suppressed").count()).isEqualTo(1.0);
  }

  @Test
  void differentMetricsAreDifferentEpisodes() {
    service.ingest(event(AlertType.THRESHOLD, Severity.HIGH, "HEART_RATE", 0), "c");
    service.ingest(event(AlertType.THRESHOLD, Severity.HIGH, "SPO2", 0), "c");
    service.ingest(event(AlertType.TREND_ZSCORE, Severity.MEDIUM, "HEART_RATE", 0), "c");

    assertThat(alerts.all()).hasSize(3);
  }

  // ---- update / idempotency ---------------------------------------------------------------

  @Test
  void laterWindowUpdatesTheEpisodeWithoutNotifyingOrCreatingARow() {
    service.ingest(hr(Severity.HIGH, 0), "c");
    UUID id = alerts.all().getFirst().id();
    AlertEvent next = hr(Severity.HIGH, 1);

    AlertIngestService.Outcome outcome = service.ingest(next, "c");

    assertThat(outcome).isEqualTo(AlertIngestService.Outcome.UPDATED);
    assertThat(alerts.all()).hasSize(1);
    Alert episode = alerts.get(id);
    assertThat(episode.occurrences()).isEqualTo(2);
    assertThat(episode.lastDetectionId()).isEqualTo(next.alertId());
    assertThat(episode.version()).isEqualTo(1);
    assertThat(outboxTypesFor(id))
        .containsExactly(
            OutboxEventType.ALERT_INDEX, OutboxEventType.ALERT_NOTIFY, OutboxEventType.ALERT_INDEX);
    assertThat(published()).extracting(AlertView::version).containsExactly(0L, 1L);
    verify(suppression, org.mockito.Mockito.times(1)).tryAcquire(any(), any(), any());
    assertThat(meters.counter("alerts.consumed", "outcome", "updated").count()).isEqualTo(1.0);
  }

  @Test
  void replayedDetectionChangesNothing() {
    AlertEvent first = hr(Severity.HIGH, 0);
    service.ingest(first, "c");
    org.mockito.Mockito.clearInvocations(outbox, liveFeed, suppression);

    AlertIngestService.Outcome outcome = service.ingest(first, "c");

    assertThat(outcome).isEqualTo(AlertIngestService.Outcome.IGNORED);
    assertThat(alerts.all().getFirst().version()).isZero();
    verifyNoInteractions(suppression, liveFeed);
    verify(outbox, never()).add(any());
    assertThat(meters.counter("alerts.consumed", "outcome", "ignored").count()).isEqualTo(1.0);
    assertThat(
            meters
                .counter("vitalstream_alerts_raised", "severity", "HIGH", "type", "THRESHOLD")
                .count())
        .isEqualTo(1.0);
  }

  @Test
  void lateDetectionIsIgnored() {
    service.ingest(hr(Severity.HIGH, 5), "c");

    assertThat(service.ingest(hr(Severity.CRITICAL, 3), "c"))
        .isEqualTo(AlertIngestService.Outcome.IGNORED);
    assertThat(alerts.all().getFirst().occurrences()).isEqualTo(1);
    assertThat(alerts.all().getFirst().severity()).isEqualTo(Severity.HIGH);
  }

  // ---- escalation / re-open ---------------------------------------------------------------

  @Test
  void severityRaiseNotifiesAgainAndCountsTheEscalationMetric() {
    service.ingest(hr(Severity.MEDIUM, 0), "c");

    AlertIngestService.Outcome outcome = service.ingest(hr(Severity.CRITICAL, 1), "c");

    assertThat(outcome).isEqualTo(AlertIngestService.Outcome.ESCALATED);
    UUID id = alerts.all().getFirst().id();
    assertThat(alerts.get(id).severity()).isEqualTo(Severity.CRITICAL);
    assertThat(outboxTypesFor(id))
        .containsExactly(
            OutboxEventType.ALERT_INDEX,
            OutboxEventType.ALERT_NOTIFY,
            OutboxEventType.ALERT_INDEX,
            OutboxEventType.ALERT_NOTIFY,
            OutboxEventType.ALERT_ESCALATE);
    assertThat(
            meters
                .counter("vitalstream_alert_episodes", "event", "escalated", "type", "THRESHOLD")
                .count())
        .isEqualTo(1.0);
  }

  @Test
  void sameWindowSeverityRaiseEscalatesWithoutCountingAnOccurrence() {
    service.ingest(hr(Severity.MEDIUM, 0), "c");

    service.ingest(hr(Severity.HIGH, 0), "c");

    Alert episode = alerts.all().getFirst();
    assertThat(episode.severity()).isEqualTo(Severity.HIGH);
    assertThat(episode.occurrences()).isEqualTo(1);
  }

  @Test
  void escalationOfAnAcknowledgedEpisodeReopensItAndNotifies() {
    service.ingest(hr(Severity.MEDIUM, 0), "c");
    Alert open = alerts.all().getFirst();
    alerts.update(open.acknowledge("dr", "seen", Instant.parse("2026-11-11T20:17:00Z")), 0);

    service.ingest(hr(Severity.HIGH, 1), "c");

    Alert reopened = alerts.get(open.id());
    assertThat(reopened.status()).isEqualTo(AlertStatus.OPEN);
    assertThat(reopened.acknowledgedBy()).isEqualTo("dr");
    assertThat(outboxTypesFor(open.id()))
        .endsWith(OutboxEventType.ALERT_INDEX, OutboxEventType.ALERT_NOTIFY);
    // the same episode id owns the suppression window, so its own escalation is never swallowed
    verify(suppression, org.mockito.Mockito.times(2))
        .tryAcquire(any(), eq(open.id().toString()), any());
  }

  @Test
  void updateWhileAcknowledgedKeepsItAcknowledgedAndCounts() {
    service.ingest(hr(Severity.MEDIUM, 0), "c");
    Alert open = alerts.all().getFirst();
    alerts.update(open.acknowledge("dr", null, Instant.parse("2026-11-11T20:17:00Z")), 0);

    service.ingest(hr(Severity.MEDIUM, 1), "c");

    assertThat(alerts.get(open.id()).status()).isEqualTo(AlertStatus.ACKNOWLEDGED);
    assertThat(alerts.get(open.id()).occurrences()).isEqualTo(2);
  }

  @Test
  void detectionAfterResolutionOpensANewEpisodeAndNotifies() {
    service.ingest(hr(Severity.HIGH, 0), "c");
    Alert first = alerts.all().getFirst();
    alerts.resolveStale(Instant.parse("2026-11-11T21:00:00Z"), Instant.parse("2026-11-11T21:00:00Z"));
    org.mockito.Mockito.clearInvocations(outbox);

    AlertIngestService.Outcome outcome = service.ingest(hr(Severity.HIGH, 60), "c");

    assertThat(outcome).isEqualTo(AlertIngestService.Outcome.OPENED);
    assertThat(alerts.all()).hasSize(2);
    Alert second = alerts.all().getLast();
    assertThat(second.id()).isNotEqualTo(first.id());
    assertThat(second.status()).isEqualTo(AlertStatus.OPEN);
    assertThat(outboxTypesFor(second.id()))
        .containsExactly(OutboxEventType.ALERT_INDEX, OutboxEventType.ALERT_NOTIFY);
  }

  @Test
  void replayOfAResolvedEpisodesFirstDetectionIsIgnored() {
    AlertEvent first = hr(Severity.HIGH, 0);
    service.ingest(first, "c");
    alerts.resolveStale(Instant.parse("2026-11-11T21:00:00Z"), Instant.parse("2026-11-11T21:00:00Z"));

    assertThat(service.ingest(first, "c")).isEqualTo(AlertIngestService.Outcome.IGNORED);
    assertThat(alerts.all()).hasSize(1);
  }

  // ---- hierarchy --------------------------------------------------------------------------

  @Test
  void findingUnderAnActiveNews2LinksToItStaysQuietAndRepublishesTheParent() {
    service.ingest(news2(Severity.HIGH, 0), "c");
    Alert parent = alerts.all().getFirst();
    org.mockito.Mockito.clearInvocations(outbox, liveFeed);

    service.ingest(hr(Severity.MEDIUM, 0), "c");

    Alert child = alerts.all().stream().filter(a -> a.type() == AlertType.THRESHOLD).findFirst().get();
    assertThat(child.parentAlertId()).isEqualTo(parent.id());
    assertThat(outboxTypesFor(child.id())).containsExactly(OutboxEventType.ALERT_INDEX);
    assertThat(outboxTypesFor(parent.id())).containsExactly(OutboxEventType.ALERT_INDEX);
    List<AlertView> views = published();
    assertThat(views).extracting(AlertView::alertId).containsExactly(child.id(), parent.id());
    assertThat(views.getLast().childCount()).isEqualTo(1);
    assertThat(views.getFirst().parentAlertId()).isEqualTo(parent.id());
    verify(suppression, org.mockito.Mockito.times(1)).tryAcquire(any(), any(), any());
  }

  @Test
  void childThatOutranksTheParentStaysTopLevelAndNotifies() {
    service.ingest(news2(Severity.MEDIUM, 0), "c");

    service.ingest(hr(Severity.CRITICAL, 0), "c");

    Alert child = alerts.all().stream().filter(a -> a.type() == AlertType.THRESHOLD).findFirst().get();
    assertThat(child.parentAlertId()).isNull();
    assertThat(outboxTypesFor(child.id()))
        .containsExactly(
            OutboxEventType.ALERT_INDEX,
            OutboxEventType.ALERT_NOTIFY,
            OutboxEventType.ALERT_ESCALATE);
  }

  @Test
  void openingNews2AdoptsTheOpenFindingsThatDoNotOutrankIt() {
    service.ingest(hr(Severity.MEDIUM, 0), "c");
    service.ingest(event(AlertType.ML_ANOMALY, Severity.CRITICAL, null, 0), "c");
    Alert quiet = alerts.all().get(0);
    Alert loud = alerts.all().get(1);
    org.mockito.Mockito.clearInvocations(outbox, liveFeed);

    service.ingest(news2(Severity.HIGH, 0), "c");

    Alert parent = alerts.all().stream().filter(a -> a.type() == AlertType.NEWS2).findFirst().get();
    assertThat(alerts.get(quiet.id()).parentAlertId()).isEqualTo(parent.id());
    assertThat(alerts.get(loud.id()).parentAlertId()).isNull();
    assertThat(outboxTypesFor(parent.id()))
        .containsExactly(OutboxEventType.ALERT_INDEX, OutboxEventType.ALERT_NOTIFY);
    assertThat(outboxTypesFor(quiet.id())).containsExactly(OutboxEventType.ALERT_INDEX);
    assertThat(outboxTypesFor(loud.id())).isEmpty();
    assertThat(published()).extracting(AlertView::alertId).containsExactly(parent.id(), quiet.id());
    assertThat(published().getFirst().childCount()).isEqualTo(1);
  }

  @Test
  void news2EscalationAdoptsFindingsItNowCovers() {
    service.ingest(news2(Severity.MEDIUM, 0), "c");
    service.ingest(hr(Severity.HIGH, 0), "c");
    Alert child = alerts.all().getLast();
    assertThat(child.parentAlertId()).isNull();

    service.ingest(news2(Severity.CRITICAL, 1), "c");

    assertThat(alerts.get(child.id()).parentAlertId()).isEqualTo(alerts.all().getFirst().id());
  }

  @Test
  void childEscalatingPastItsParentIsUnlinkedAndNotifies() {
    service.ingest(news2(Severity.HIGH, 0), "c");
    service.ingest(hr(Severity.MEDIUM, 0), "c");
    Alert parent = alerts.all().getFirst();
    Alert child = alerts.all().getLast();
    assertThat(child.parentAlertId()).isEqualTo(parent.id());
    org.mockito.Mockito.clearInvocations(outbox, liveFeed);

    service.ingest(hr(Severity.CRITICAL, 1), "c");

    assertThat(alerts.get(child.id()).parentAlertId()).isNull();
    assertThat(outboxTypesFor(child.id()))
        .containsExactly(
            OutboxEventType.ALERT_INDEX,
            OutboxEventType.ALERT_NOTIFY,
            OutboxEventType.ALERT_ESCALATE);
    List<AlertView> views = published();
    assertThat(views).extracting(AlertView::alertId).containsExactly(child.id(), parent.id());
    assertThat(views.getLast().childCount()).isZero();
  }

  @Test
  void aResolvedNews2DoesNotAdopt() {
    service.ingest(news2(Severity.CRITICAL, 0), "c");
    alerts.resolveStale(Instant.parse("2026-11-11T21:00:00Z"), Instant.parse("2026-11-11T21:00:00Z"));

    service.ingest(hr(Severity.LOW, 60), "c");

    assertThat(alerts.all().getLast().parentAlertId()).isNull();
  }

  // ---- concurrency ------------------------------------------------------------------------

  @Test
  void lostCompareAndSetIsRetriedAgainstFreshState() {
    service.ingest(hr(Severity.HIGH, 0), "c");
    alerts.failingWrites = 2;

    assertThat(service.ingest(hr(Severity.HIGH, 1), "c"))
        .isEqualTo(AlertIngestService.Outcome.UPDATED);
    assertThat(alerts.all().getFirst().occurrences()).isEqualTo(2);
  }

  @Test
  void givesUpAfterThreeLostRaces() {
    service.ingest(hr(Severity.HIGH, 0), "c");
    alerts.failingWrites = 3;

    assertThatThrownBy(() -> service.ingest(hr(Severity.HIGH, 1), "c"))
        .isInstanceOf(ConcurrentAlertUpdateException.class);
    assertThat(alerts.all().getFirst().occurrences()).isEqualTo(1);
  }

  @Test
  void concurrentInsertOfTheSameKeyFallsBackToAnUpdate() {
    InMemoryAlertRepository racing =
        new InMemoryAlertRepository() {
          private boolean raced;

          @Override
          public boolean insert(Alert episode) {
            if (!raced) {
              raced = true;
              super.insert(Alert.open(TestAlerts.detection(Severity.HIGH, 0)));
            }
            return super.insert(episode);
          }
        };
    AlertIngestService racingService =
        new AlertIngestService(
            racing,
            outbox,
            suppression,
            liveFeed,
            new DirectUnitOfWork(),
            new OutboxPayloads(JsonMapper.builder().build()),
            Clock.fixed(Instant.parse("2026-11-11T20:20:00Z"), ZoneOffset.UTC),
            meters);

    AlertIngestService.Outcome outcome = racingService.ingest(hr(Severity.HIGH, 1), "c");

    assertThat(outcome).isEqualTo(AlertIngestService.Outcome.UPDATED);
    assertThat(racing.all()).hasSize(1);
    assertThat(racing.all().getFirst().occurrences()).isEqualTo(2);
  }

  // ---- robustness -------------------------------------------------------------------------

  @Test
  void liveFeedFailureDoesNotFailIngestion() {
    doThrow(new IllegalStateException("redis down")).when(liveFeed).publish(any());

    assertThat(service.ingest(hr(Severity.LOW, 0), "c"))
        .isEqualTo(AlertIngestService.Outcome.OPENED);
  }

  @Test
  void eventCompletenessRequiresIdPatientTypeSeverityWindowAndDetectionTime() {
    assertThat(hr(Severity.LOW, 0).isComplete()).isTrue();
    assertThat(
            Stream.of(
                    withoutId(hr(Severity.LOW, 0)),
                    withPatient(hr(Severity.LOW, 0), " "),
                    withoutWindow(hr(Severity.LOW, 0)))
                .anyMatch(AlertEvent::isComplete))
        .isFalse();
  }

  private static AlertEvent withoutId(AlertEvent e) {
    return new AlertEvent(
        null,
        e.patientId(),
        e.type(),
        e.severity(),
        e.metric(),
        e.value(),
        e.score(),
        e.message(),
        e.windowStart(),
        e.windowEnd(),
        e.detectedAt());
  }

  private static AlertEvent withPatient(AlertEvent e, String patient) {
    return new AlertEvent(
        e.alertId(),
        patient,
        e.type(),
        e.severity(),
        e.metric(),
        e.value(),
        e.score(),
        e.message(),
        e.windowStart(),
        e.windowEnd(),
        e.detectedAt());
  }

  private static AlertEvent withoutWindow(AlertEvent e) {
    return new AlertEvent(
        e.alertId(),
        e.patientId(),
        e.type(),
        e.severity(),
        e.metric(),
        e.value(),
        e.score(),
        e.message(),
        null,
        null,
        e.detectedAt());
  }
}
