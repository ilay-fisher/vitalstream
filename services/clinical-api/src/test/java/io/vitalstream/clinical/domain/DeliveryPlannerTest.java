package io.vitalstream.clinical.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class DeliveryPlannerTest {

  @Test
  void openingACriticalTopLevelEpisodeIsIndexedNotifiedAndEscalated() {
    List<OutboxEventType> plan =
        DeliveryPlanner.plan(TestAlerts.alert(Severity.CRITICAL), EpisodeChange.OPENED, false);

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
    assertThat(DeliveryPlanner.plan(TestAlerts.alert(severity), EpisodeChange.OPENED, false))
        .containsExactly(OutboxEventType.ALERT_INDEX, OutboxEventType.ALERT_NOTIFY);
  }

  @Test
  void anEscalationOfATopLevelEpisodeNotifies() {
    assertThat(
            DeliveryPlanner.plan(TestAlerts.alert(Severity.HIGH), EpisodeChange.ESCALATED, false))
        .containsExactly(OutboxEventType.ALERT_INDEX, OutboxEventType.ALERT_NOTIFY);
  }

  @Test
  void plainUpdatesOnlyReindex() {
    assertThat(
            DeliveryPlanner.plan(TestAlerts.alert(Severity.CRITICAL), EpisodeChange.UPDATED, false))
        .containsExactly(OutboxEventType.ALERT_INDEX);
  }

  @ParameterizedTest
  @EnumSource(EpisodeChange.class)
  void childrenNeverNotify(EpisodeChange change) {
    Alert child = TestAlerts.alert(Severity.CRITICAL).withParent(UUID.randomUUID());

    assertThat(DeliveryPlanner.notifies(child, change)).isFalse();
    assertThat(DeliveryPlanner.plan(child, change, false))
        .containsExactly(OutboxEventType.ALERT_INDEX);
  }

  @ParameterizedTest
  @EnumSource(Severity.class)
  void suppressedEpisodesAreOnlyIndexed(Severity severity) {
    assertThat(DeliveryPlanner.plan(TestAlerts.alert(severity), EpisodeChange.OPENED, true))
        .containsExactly(OutboxEventType.ALERT_INDEX);
  }

  @Test
  void onlyOpenAndEscalationOfTopLevelEpisodesNotify() {
    Alert top = TestAlerts.alert(Severity.LOW);

    assertThat(DeliveryPlanner.notifies(top, EpisodeChange.OPENED)).isTrue();
    assertThat(DeliveryPlanner.notifies(top, EpisodeChange.ESCALATED)).isTrue();
    assertThat(DeliveryPlanner.notifies(top, EpisodeChange.UPDATED)).isFalse();
  }

  @ParameterizedTest
  @EnumSource(Severity.class)
  void routingKeyIsLowercaseSeverityUnderAlertPrefix(Severity severity) {
    assertThat(severity.routingKey()).isEqualTo("alert." + severity.name().toLowerCase());
  }
}
