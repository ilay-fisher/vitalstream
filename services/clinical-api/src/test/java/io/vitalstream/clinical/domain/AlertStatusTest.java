package io.vitalstream.clinical.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AlertStatusTest {

  @Test
  void openAlertCanBeAcknowledgedOrResolved() {
    assertThat(AlertStatus.OPEN.canTransitionTo(AlertStatus.ACKNOWLEDGED)).isTrue();
    assertThat(AlertStatus.OPEN.canTransitionTo(AlertStatus.RESOLVED)).isTrue();
  }

  @Test
  void acknowledgedAlertCanOnlyBeResolved() {
    assertThat(AlertStatus.ACKNOWLEDGED.canTransitionTo(AlertStatus.RESOLVED)).isTrue();
    assertThat(AlertStatus.ACKNOWLEDGED.canTransitionTo(AlertStatus.ACKNOWLEDGED)).isFalse();
    assertThat(AlertStatus.ACKNOWLEDGED.canTransitionTo(AlertStatus.OPEN)).isFalse();
  }

  @Test
  void resolvedIsTerminal() {
    for (AlertStatus target : AlertStatus.values()) {
      assertThat(AlertStatus.RESOLVED.canTransitionTo(target)).isFalse();
    }
  }
}
