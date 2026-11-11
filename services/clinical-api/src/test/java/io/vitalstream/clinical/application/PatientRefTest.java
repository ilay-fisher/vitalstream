package io.vitalstream.clinical.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PatientRefTest {

  @Test
  void matchesTheCrossServiceTestVector() {
    assertThat(PatientRef.of("P-0001", "dev-only-pseudonym-key")).isEqualTo("26043831372c");
  }

  @Test
  void differentKeyOrPatientGivesDifferentRef() {
    assertThat(PatientRef.of("P-0001", "other")).isNotEqualTo("26043831372c");
    assertThat(PatientRef.of("P-0002", "dev-only-pseudonym-key")).isNotEqualTo("26043831372c");
  }

  @Test
  void defaultKeyIsUsedWhenNoEnvIsSet() {
    assertThat(PatientRef.of("P-0001")).hasSize(12);
  }
}
