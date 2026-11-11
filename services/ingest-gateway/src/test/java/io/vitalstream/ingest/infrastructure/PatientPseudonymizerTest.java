package io.vitalstream.ingest.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import io.vitalstream.ingest.infrastructure.logging.PatientPseudonymizer;
import org.junit.jupiter.api.Test;

class PatientPseudonymizerTest {

  @Test
  void matchesPinnedCrossServiceTestVector() {
    var pseudonymizer = new PatientPseudonymizer("dev-only-pseudonym-key");

    assertThat(pseudonymizer.ref("P-0001")).isEqualTo("26043831372c");
  }

  @Test
  void isStableTwelveHexCharsAndDependsOnKeyAndInput() {
    var a = new PatientPseudonymizer("key-a");
    var b = new PatientPseudonymizer("key-b");

    assertThat(a.ref("P-0002")).matches("[0-9a-f]{12}").isEqualTo(a.ref("P-0002"));
    assertThat(a.ref("P-0002")).isNotEqualTo(a.ref("P-0003")).isNotEqualTo(b.ref("P-0002"));
  }

  @Test
  void nullPatientYieldsPlaceholder() {
    assertThat(new PatientPseudonymizer("k").ref(null)).isEqualTo("none");
  }
}
