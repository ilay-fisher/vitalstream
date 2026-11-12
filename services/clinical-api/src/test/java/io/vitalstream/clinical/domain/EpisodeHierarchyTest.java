package io.vitalstream.clinical.domain;

import static io.vitalstream.clinical.domain.TestAlerts.detection;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** NEWS2-as-parent rules of CONTRACTS section 3.4. */
class EpisodeHierarchyTest {

  private static Alert news2(Severity severity) {
    return Alert.open(detection(AlertType.NEWS2, severity, null, 0));
  }

  private static Alert child(AlertType type, Severity severity) {
    return Alert.open(detection(type, severity, "HEART_RATE", 0));
  }

  @ParameterizedTest
  @EnumSource(
      value = AlertType.class,
      names = {"THRESHOLD", "TREND_ZSCORE", "ML_ANOMALY"})
  void childOfEqualOrLowerSeverityLinksToTheParent(AlertType type) {
    Alert parent = news2(Severity.HIGH);

    assertThat(EpisodeHierarchy.parentFor(child(type, Severity.HIGH), Optional.of(parent)))
        .isEqualTo(parent.id());
    assertThat(EpisodeHierarchy.parentFor(child(type, Severity.MEDIUM), Optional.of(parent)))
        .isEqualTo(parent.id());
  }

  @Test
  void childThatOutranksTheParentStaysTopLevel() {
    Alert parent = news2(Severity.MEDIUM);

    assertThat(
            EpisodeHierarchy.parentFor(
                child(AlertType.THRESHOLD, Severity.CRITICAL), Optional.of(parent)))
        .isNull();
  }

  @Test
  void withoutAParentEpisodeEverythingIsTopLevel() {
    assertThat(
            EpisodeHierarchy.parentFor(
                child(AlertType.THRESHOLD, Severity.LOW), Optional.empty()))
        .isNull();
  }

  @Test
  void aResolvedParentDoesNotAdopt() {
    Alert parent = news2(Severity.CRITICAL).withStatus(AlertStatus.RESOLVED);

    assertThat(
            EpisodeHierarchy.parentFor(
                child(AlertType.THRESHOLD, Severity.LOW), Optional.of(parent)))
        .isNull();
  }

  @Test
  void news2NeverHasAParent() {
    assertThat(EpisodeHierarchy.parentFor(news2(Severity.LOW), Optional.of(news2(Severity.HIGH))))
        .isNull();
  }

  @Test
  void escalatingPastTheParentUnlinksOnRecheck() {
    Alert parent = news2(Severity.HIGH);
    Alert linked = child(AlertType.THRESHOLD, Severity.MEDIUM).withParent(parent.id());
    Alert escalated =
        linked
            .absorb(detection(AlertType.THRESHOLD, Severity.CRITICAL, "HEART_RATE", 1))
            .orElseThrow()
            .episode();

    assertThat(EpisodeHierarchy.parentFor(escalated, Optional.of(parent))).isNull();
  }

  @ParameterizedTest
  @EnumSource(Severity.class)
  void severitiesUpToListsEverythingNotAbove(Severity max) {
    assertThat(Severity.upTo(max)).allMatch(s -> !s.isHigherThan(max)).contains(max);
    assertThat(Severity.upTo(max)).hasSize(max.ordinal() + 1);
  }
}
