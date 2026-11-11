package io.vitalstream.clinical.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class DeliveryPlannerTest {

  @Test
  void criticalAlertIsIndexedNotifiedAndEscalated() {
    List<OutboxEventType> plan = DeliveryPlanner.plan(TestAlerts.alert(Severity.CRITICAL), false);

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
  void nonCriticalAlertsAreNeverEscalated(Severity severity) {
    assertThat(DeliveryPlanner.plan(TestAlerts.alert(severity), false))
        .containsExactly(OutboxEventType.ALERT_INDEX, OutboxEventType.ALERT_NOTIFY);
  }

  @ParameterizedTest
  @EnumSource(Severity.class)
  void suppressedAlertsAreOnlyIndexed(Severity severity) {
    assertThat(DeliveryPlanner.plan(TestAlerts.alert(severity), true))
        .containsExactly(OutboxEventType.ALERT_INDEX);
  }

  @ParameterizedTest
  @EnumSource(Severity.class)
  void routingKeyIsLowercaseSeverityUnderAlertPrefix(Severity severity) {
    assertThat(severity.routingKey()).isEqualTo("alert." + severity.name().toLowerCase());
  }
}
