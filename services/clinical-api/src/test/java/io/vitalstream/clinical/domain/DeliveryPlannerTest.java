package io.vitalstream.clinical.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class DeliveryPlannerTest {

  /** A cutoff before every fixture episode: nothing is stale. */
  private static final Instant LONG_AGO = Instant.EPOCH;

  @Test
  void openingACriticalTopLevelEpisodeIsIndexedNotifiedAndEscalated() {
    List<OutboxEventType> plan =
        DeliveryPlanner.plan(
            TestAlerts.alert(Severity.CRITICAL), EpisodeChange.OPENED, false, LONG_AGO);

    assertThat(plan)
        .containsExactly(
            OutboxEventType.ALERT_INDEX,
            OutboxEventType.ALERT_NOTIFY,
            OutboxEventType.ALERT_ESCALATE);
  }

  @ParameterizedTest
  @EnumSource(
      value = Severity.class,
      names = {"LOW", "MEDIUM", "HIGH"})
  void nonCriticalEpisodesAreNeverEscalated(Severity severity) {
    assertThat(
            DeliveryPlanner.plan(TestAlerts.alert(severity), EpisodeChange.OPENED, false, LONG_AGO))
        .containsExactly(OutboxEventType.ALERT_INDEX, OutboxEventType.ALERT_NOTIFY);
  }

  @Test
  void anEscalationOfATopLevelEpisodeNotifies() {
    assertThat(
            DeliveryPlanner.plan(
                TestAlerts.alert(Severity.HIGH), EpisodeChange.ESCALATED, false, LONG_AGO))
        .containsExactly(OutboxEventType.ALERT_INDEX, OutboxEventType.ALERT_NOTIFY);
  }

  @Test
  void plainUpdatesOnlyReindex() {
    assertThat(
            DeliveryPlanner.plan(
                TestAlerts.alert(Severity.CRITICAL), EpisodeChange.UPDATED, false, LONG_AGO))
        .containsExactly(OutboxEventType.ALERT_INDEX);
  }

  @ParameterizedTest
  @EnumSource(EpisodeChange.class)
  void childrenNeverNotify(EpisodeChange change) {
    Alert child = TestAlerts.alert(Severity.CRITICAL).withParent(UUID.randomUUID());

    assertThat(DeliveryPlanner.notifies(child, change, LONG_AGO)).isFalse();
    assertThat(DeliveryPlanner.plan(child, change, false, LONG_AGO))
        .containsExactly(OutboxEventType.ALERT_INDEX);
  }

  @ParameterizedTest
  @EnumSource(Severity.class)
  void suppressedEpisodesAreOnlyIndexed(Severity severity) {
    assertThat(
            DeliveryPlanner.plan(TestAlerts.alert(severity), EpisodeChange.OPENED, true, LONG_AGO))
        .containsExactly(OutboxEventType.ALERT_INDEX);
  }

  @Test
  void onlyOpenAndEscalationOfTopLevelEpisodesNotify() {
    Alert top = TestAlerts.alert(Severity.LOW);

    assertThat(DeliveryPlanner.notifies(top, EpisodeChange.OPENED, LONG_AGO)).isTrue();
    assertThat(DeliveryPlanner.notifies(top, EpisodeChange.ESCALATED, LONG_AGO)).isTrue();
    assertThat(DeliveryPlanner.notifies(top, EpisodeChange.UPDATED, LONG_AGO)).isFalse();
  }

  @ParameterizedTest
  @EnumSource(Severity.class)
  void routingKeyIsLowercaseSeverityUnderAlertPrefix(Severity severity) {
    assertThat(severity.routingKey()).isEqualTo("alert." + severity.name().toLowerCase());
  }

  @Test
  void unlinkedOpenEpisodePagesButAnAcknowledgedOneDoesNot() {
    Alert open = TestAlerts.alert(Severity.HIGH);
    Alert acked = open.acknowledge("dr", null, TestAlerts.T0);

    assertThat(DeliveryPlanner.notifies(open, EpisodeChange.UNLINKED, LONG_AGO)).isTrue();
    assertThat(DeliveryPlanner.notifies(acked, EpisodeChange.UNLINKED, LONG_AGO)).isFalse();
    assertThat(DeliveryPlanner.plan(open, EpisodeChange.UNLINKED, false, LONG_AGO))
        .containsExactly(OutboxEventType.ALERT_INDEX, OutboxEventType.ALERT_NOTIFY);
  }

  // ---- staleness (CONTRACTS section 3.4): lastSeenAt < now - ALERT_RESOLVE_AFTER never pages ----

  /** Cutoff equal to lastSeenAt: the episode is exactly at the threshold, which is not stale. */
  private static Instant cutoffAt(Alert episode) {
    return episode.lastSeenAt();
  }

  @Test
  void aFreshOpenEpisodeNotifies() {
    Alert open = TestAlerts.alert(Severity.CRITICAL);

    assertThat(DeliveryPlanner.notifies(open, EpisodeChange.OPENED, cutoffAt(open).minusSeconds(1)))
        .isTrue();
    assertThat(
            DeliveryPlanner.plan(open, EpisodeChange.OPENED, false, cutoffAt(open).minusSeconds(1)))
        .containsExactly(
            OutboxEventType.ALERT_INDEX,
            OutboxEventType.ALERT_NOTIFY,
            OutboxEventType.ALERT_ESCALATE);
  }

  @Test
  void aStaleOpenEpisodeIsOnlyIndexed() {
    Alert open = TestAlerts.alert(Severity.CRITICAL);
    Instant cutoff = cutoffAt(open).plusSeconds(1);

    assertThat(DeliveryPlanner.notifies(open, EpisodeChange.OPENED, cutoff)).isFalse();
    assertThat(DeliveryPlanner.skippedAsStale(open, EpisodeChange.OPENED, cutoff)).isTrue();
    assertThat(DeliveryPlanner.plan(open, EpisodeChange.OPENED, false, cutoff))
        .containsExactly(OutboxEventType.ALERT_INDEX);
  }

  @Test
  void aStaleEscalationIsOnlyIndexed() {
    Alert escalated = TestAlerts.alert(Severity.CRITICAL);
    Instant cutoff = cutoffAt(escalated).plusSeconds(1);

    assertThat(DeliveryPlanner.skippedAsStale(escalated, EpisodeChange.ESCALATED, cutoff)).isTrue();
    assertThat(DeliveryPlanner.plan(escalated, EpisodeChange.ESCALATED, false, cutoff))
        .containsExactly(OutboxEventType.ALERT_INDEX);
  }

  @Test
  void aStaleUnlinkWhileOpenIsOnlyIndexed() {
    Alert unlinked = TestAlerts.alert(Severity.HIGH);
    Instant cutoff = cutoffAt(unlinked).plusSeconds(1);

    assertThat(DeliveryPlanner.skippedAsStale(unlinked, EpisodeChange.UNLINKED, cutoff)).isTrue();
    assertThat(DeliveryPlanner.plan(unlinked, EpisodeChange.UNLINKED, false, cutoff))
        .containsExactly(OutboxEventType.ALERT_INDEX);
  }

  @Test
  void anEpisodeExactlyAtTheThresholdIsNotStale() {
    Alert open = TestAlerts.alert(Severity.HIGH);

    assertThat(DeliveryPlanner.notifies(open, EpisodeChange.OPENED, cutoffAt(open))).isTrue();
    assertThat(DeliveryPlanner.skippedAsStale(open, EpisodeChange.OPENED, cutoffAt(open)))
        .isFalse();
  }

  @Test
  void onlyChangesThatWouldHaveNotifiedCountAsSkipped() {
    Alert top = TestAlerts.alert(Severity.HIGH);
    Alert child = top.withParent(UUID.randomUUID());
    Alert acked = top.acknowledge("dr", null, TestAlerts.T0);
    Instant cutoff = cutoffAt(top).plusSeconds(1);

    assertThat(DeliveryPlanner.skippedAsStale(top, EpisodeChange.UPDATED, cutoff)).isFalse();
    assertThat(DeliveryPlanner.skippedAsStale(child, EpisodeChange.OPENED, cutoff)).isFalse();
    assertThat(DeliveryPlanner.skippedAsStale(acked, EpisodeChange.UNLINKED, cutoff)).isFalse();
  }
}
