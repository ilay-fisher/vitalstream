package io.vitalstream.clinical.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vitalstream.clinical.domain.Alert;
import io.vitalstream.clinical.domain.AlertStatus;
import io.vitalstream.clinical.domain.AlertType;
import io.vitalstream.clinical.domain.OutboxEventType;
import io.vitalstream.clinical.domain.Severity;
import io.vitalstream.clinical.domain.TestAlerts;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

/** Auto-resolution after silence (CONTRACTS section 3.4, "Resolve"). */
class EpisodeResolverTest {

  /** Episodes of TestAlerts last seen at 20:16:00. */
  private static final Instant SEEN = Instant.parse("2026-11-11T20:16:00Z");

  private final InMemoryAlertRepository alerts = new InMemoryAlertRepository();
  private final OutboxRepository outbox = mock(OutboxRepository.class);
  private final LiveFeedPublisher liveFeed = mock(LiveFeedPublisher.class);
  private final SuppressionStore suppression = mock(SuppressionStore.class);
  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

  private EpisodeResolver resolverAt(Instant now) {
    return new EpisodeResolver(
        alerts,
        outbox,
        suppression,
        liveFeed,
        new DirectUnitOfWork(),
        new OutboxPayloads(JsonMapper.builder().build()),
        Clock.fixed(now, ZoneOffset.UTC),
        meters,
        Duration.ofMinutes(5));
  }

  private List<OutboxEntry> outboxRows() {
    ArgumentCaptor<OutboxEntry> rows = ArgumentCaptor.forClass(OutboxEntry.class);
    verify(outbox, org.mockito.Mockito.atLeast(0)).add(rows.capture());
    return rows.getAllValues();
  }

  @BeforeEach
  void quiet() {
    org.mockito.Mockito.reset(outbox, liveFeed, suppression);
    org.mockito.Mockito.when(suppression.tryAcquire(any(), any(), any())).thenReturn(true);
  }

  @Test
  void resolvesEpisodesSilentForLongerThanTheConfiguredPeriod() {
    Alert stale = TestAlerts.alert(Severity.HIGH);
    alerts.seed(stale);

    int resolved = resolverAt(SEEN.plus(Duration.ofMinutes(5)).plusSeconds(1)).resolveStale();

    assertThat(resolved).isEqualTo(1);
    Alert after = alerts.get(stale.id());
    assertThat(after.status()).isEqualTo(AlertStatus.RESOLVED);
    assertThat(after.resolvedAt()).isEqualTo(SEEN.plus(Duration.ofMinutes(5)).plusSeconds(1));
    assertThat(after.version()).isEqualTo(1);
    assertThat(outboxRows())
        .extracting(OutboxEntry::eventType)
        .containsExactly(OutboxEventType.ALERT_INDEX);
    ArgumentCaptor<AlertView> view = ArgumentCaptor.forClass(AlertView.class);
    verify(liveFeed).publish(view.capture());
    assertThat(view.getValue().status()).isEqualTo(AlertStatus.RESOLVED);
    assertThat(
            meters
                .counter("vitalstream_alert_episodes", "event", "resolved", "type", "THRESHOLD")
                .count())
        .isEqualTo(1.0);
  }

  @Test
  void leavesEpisodesSeenWithinThePeriodAlone() {
    Alert fresh = TestAlerts.alert(Severity.HIGH);
    alerts.seed(fresh);

    assertThat(resolverAt(SEEN.plus(Duration.ofMinutes(5))).resolveStale()).isZero();

    assertThat(alerts.get(fresh.id()).status()).isEqualTo(AlertStatus.OPEN);
    verify(outbox, never()).add(any());
    verify(liveFeed, never()).publish(any());
  }

  @Test
  void acknowledgedEpisodesResolveToo() {
    Alert acked = TestAlerts.alert(Severity.HIGH).acknowledge("dr", null, SEEN);
    alerts.seed(acked);

    resolverAt(SEEN.plus(Duration.ofHours(1))).resolveStale();

    Alert after = alerts.get(acked.id());
    assertThat(after.status()).isEqualTo(AlertStatus.RESOLVED);
    assertThat(after.acknowledgedBy()).isEqualTo("dr");
  }

  @Test
  void runningTwiceIsIdempotent() {
    alerts.seed(TestAlerts.alert(Severity.HIGH));
    EpisodeResolver resolver = resolverAt(SEEN.plus(Duration.ofHours(1)));

    assertThat(resolver.resolveStale()).isEqualTo(1);
    assertThat(resolver.resolveStale()).isZero();

    assertThat(alerts.all().getFirst().version()).isEqualTo(1);
    assertThat(outboxRows()).hasSize(1);
  }

  @Test
  void resolvingAParentUnlinksItsStillOpenChildrenAndRepublishesThem() {
    Alert parent = Alert.open(TestAlerts.detection(AlertType.NEWS2, Severity.HIGH, null, 0));
    Alert child = Alert.open(TestAlerts.detection(Severity.MEDIUM, 3)).withParent(parent.id());
    alerts.seed(parent);
    alerts.seed(child);

    int resolved = resolverAt(SEEN.plus(Duration.ofMinutes(5)).plusSeconds(1)).resolveStale();

    assertThat(resolved).isEqualTo(1);
    assertThat(alerts.get(parent.id()).status()).isEqualTo(AlertStatus.RESOLVED);
    Alert orphan = alerts.get(child.id());
    assertThat(orphan.status()).isEqualTo(AlertStatus.OPEN);
    assertThat(orphan.parentAlertId()).isNull();
    assertThat(orphan.version()).isEqualTo(1);
    assertThat(outboxRows())
        .extracting(OutboxEntry::aggregateId)
        .containsOnly(parent.id(), child.id());
    ArgumentCaptor<AlertView> views = ArgumentCaptor.forClass(AlertView.class);
    verify(liveFeed, org.mockito.Mockito.times(2)).publish(views.capture());
    assertThat(views.getAllValues())
        .extracting(AlertView::alertId)
        .containsExactlyInAnyOrder(parent.id(), child.id());
  }

  @Test
  void aResolvedChildRepublishesItsLiveParentWithTheNewChildCount() {
    Alert parent = Alert.open(TestAlerts.detection(AlertType.NEWS2, Severity.HIGH, null, 3));
    Alert child = Alert.open(TestAlerts.detection(Severity.MEDIUM, 0)).withParent(parent.id());
    alerts.seed(parent);
    alerts.seed(child);

    resolverAt(SEEN.plus(Duration.ofMinutes(5)).plusSeconds(1)).resolveStale();

    assertThat(alerts.get(child.id()).status()).isEqualTo(AlertStatus.RESOLVED);
    assertThat(alerts.get(child.id()).parentAlertId()).isEqualTo(parent.id());
    ArgumentCaptor<AlertView> views = ArgumentCaptor.forClass(AlertView.class);
    verify(liveFeed, org.mockito.Mockito.times(2)).publish(views.capture());
    AlertView parentView =
        views.getAllValues().stream()
            .filter(v -> v.alertId().equals(parent.id()))
            .findFirst()
            .get();
    assertThat(parentView.childCount()).isZero();
    assertThat(parentView.version()).isEqualTo(1);
  }

  @Test
  void liveFeedFailureDoesNotFailTheSweep() {
    alerts.seed(TestAlerts.alert(Severity.HIGH));
    doThrow(new IllegalStateException("redis down")).when(liveFeed).publish(any());

    assertThat(resolverAt(SEEN.plus(Duration.ofHours(1))).resolveStale()).isEqualTo(1);
  }

  @Test
  void aSilentChildUnlinkedByItsParentIsNotPagedAndIsCounted() {
    // parent and child are both already silent for longer than the threshold at sweep time
    Alert parent = Alert.open(TestAlerts.detection(AlertType.NEWS2, Severity.HIGH, null, -2));
    Alert child = Alert.open(TestAlerts.detection(Severity.MEDIUM, 0)).withParent(parent.id());
    // a sweep that resolves only the parent leaves the silent child to be unlinked (a race
    // between the resolver statement and a concurrent write can produce this in production)
    InMemoryAlertRepository onlyParentDue =
        new InMemoryAlertRepository() {
          @Override
          public List<ResolvedEpisode> resolveStale(Instant cutoff, Instant now) {
            return super.resolveStale(cutoff.minusSeconds(60), now);
          }
        };
    onlyParentDue.seed(parent);
    onlyParentDue.seed(child);
    EpisodeResolver resolver =
        new EpisodeResolver(
            onlyParentDue,
            outbox,
            suppression,
            liveFeed,
            new DirectUnitOfWork(),
            new OutboxPayloads(JsonMapper.builder().build()),
            Clock.fixed(AFTER_PARENT, ZoneOffset.UTC),
            meters,
            Duration.ofMinutes(5));

    resolver.resolveStale();

    assertThat(onlyParentDue.get(child.id()).parentAlertId()).isNull();
    assertThat(outboxRows())
        .filteredOn(r -> r.aggregateId().equals(child.id()))
        .extracting(OutboxEntry::eventType)
        .containsExactly(OutboxEventType.ALERT_INDEX);
    assertThat(meters.counter("vitalstream.alert.notifications.skipped", "reason", "stale").count())
        .isEqualTo(1.0);
    verify(liveFeed, org.mockito.Mockito.atLeast(2)).publish(any());
  }

  private Alert[] parentWithChild(boolean acknowledged) {
    Alert parent = Alert.open(TestAlerts.detection(AlertType.NEWS2, Severity.HIGH, null, 0));
    Alert child = Alert.open(TestAlerts.detection(Severity.MEDIUM, 3)).withParent(parent.id());
    if (acknowledged) {
      child = child.acknowledge("dr", null, SEEN).withParent(parent.id());
    }
    alerts.seed(parent);
    alerts.seed(child);
    return new Alert[] {parent, child};
  }

  private static final Instant AFTER_PARENT = SEEN.plus(Duration.ofMinutes(5)).plusSeconds(1);

  @Test
  void anOpenChildUnlinkedByItsParentsResolutionPagesOnce() {
    Alert child = parentWithChild(false)[1];
    EpisodeResolver resolver = resolverAt(AFTER_PARENT);

    resolver.resolveStale();
    resolver.resolveStale();
    resolver.resolveStale();

    assertThat(outboxRows())
        .filteredOn(r -> r.aggregateId().equals(child.id()))
        .extracting(OutboxEntry::eventType)
        .containsExactly(OutboxEventType.ALERT_INDEX, OutboxEventType.ALERT_NOTIFY);
    verify(suppression)
        .tryAcquire(
            org.mockito.ArgumentMatchers.eq("alert:suppress:P-0001:THRESHOLD:HEART_RATE"),
            org.mockito.ArgumentMatchers.eq(child.id().toString()),
            any());
    assertThat(
            meters
                .counter("vitalstream_alert_episodes", "event", "unlinked", "type", "THRESHOLD")
                .count())
        .isEqualTo(1.0);
  }

  @Test
  void anAcknowledgedUnlinkedChildStaysQuiet() {
    Alert child = parentWithChild(true)[1];

    resolverAt(AFTER_PARENT).resolveStale();

    assertThat(alerts.get(child.id()).parentAlertId()).isNull();
    assertThat(outboxRows())
        .filteredOn(r -> r.aggregateId().equals(child.id()))
        .extracting(OutboxEntry::eventType)
        .containsExactly(OutboxEventType.ALERT_INDEX);
  }

  @Test
  void aSuppressedUnlinkIsIndexedButNotNotified() {
    Alert child = parentWithChild(false)[1];
    org.mockito.Mockito.when(suppression.tryAcquire(any(), any(), any())).thenReturn(false);

    resolverAt(AFTER_PARENT).resolveStale();

    assertThat(outboxRows())
        .filteredOn(r -> r.aggregateId().equals(child.id()))
        .extracting(OutboxEntry::eventType)
        .containsExactly(OutboxEventType.ALERT_INDEX);
  }
}
