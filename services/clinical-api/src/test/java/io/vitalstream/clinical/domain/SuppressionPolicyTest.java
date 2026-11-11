package io.vitalstream.clinical.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SuppressionPolicyTest {

  @Test
  void keyFollowsTheRedisContract() {
    Alert alert =
        TestAlerts.alert(
            UUID.randomUUID(), "P-0007", AlertType.THRESHOLD, Severity.CRITICAL, "SPO2");

    assertThat(SuppressionPolicy.keyFor(alert)).isEqualTo("alert:suppress:P-0007:THRESHOLD:SPO2");
  }

  @Test
  void alertsWithoutMetricUseAPlaceholderSegment() {
    Alert alert =
        TestAlerts.alert(UUID.randomUUID(), "P-0007", AlertType.NEWS2, Severity.HIGH, null);

    assertThat(SuppressionPolicy.keyFor(alert)).isEqualTo("alert:suppress:P-0007:NEWS2:-");
  }

  @Test
  void windowIsFiveMinutes() {
    assertThat(SuppressionPolicy.WINDOW).isEqualTo(Duration.ofMinutes(5));
  }
}
