package io.vitalstream.clinical.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class AlertTest {

  private static final Instant NOW = Instant.parse("2026-11-11T20:20:00Z");

  @Test
  void newAlertStartsOpenAtVersionZero() {
    Alert alert = TestAlerts.alert(Severity.HIGH);

    assertThat(alert.status()).isEqualTo(AlertStatus.OPEN);
    assertThat(alert.version()).isZero();
    assertThat(alert.acknowledgedBy()).isNull();
  }

  @Test
  void acknowledgeRecordsWhoWhenAndWhy() {
    Alert acked = TestAlerts.alert(Severity.HIGH).acknowledge("dr.cohen", "on my way", NOW);

    assertThat(acked.status()).isEqualTo(AlertStatus.ACKNOWLEDGED);
    assertThat(acked.acknowledgedBy()).isEqualTo("dr.cohen");
    assertThat(acked.acknowledgedAt()).isEqualTo(NOW);
    assertThat(acked.acknowledgementNote()).isEqualTo("on my way");
  }

  @Test
  void acknowledgingTwiceIsRejected() {
    Alert acked = TestAlerts.alert(Severity.HIGH).acknowledge("dr.cohen", null, NOW);

    assertThatThrownBy(() -> acked.acknowledge("nurse.levi", null, NOW))
        .isInstanceOf(InvalidStatusTransitionException.class)
        .hasMessageContaining("ACKNOWLEDGED");
  }

  @Test
  void acknowledgeDoesNotMutateTheOriginal() {
    Alert original = TestAlerts.alert(Severity.LOW);
    original.acknowledge("dr.cohen", null, NOW);

    assertThat(original.status()).isEqualTo(AlertStatus.OPEN);
  }
}
