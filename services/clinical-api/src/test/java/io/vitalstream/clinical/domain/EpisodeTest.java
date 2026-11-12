package io.vitalstream.clinical.domain;

import static io.vitalstream.clinical.domain.TestAlerts.detection;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Open / update / idempotency / escalation rules of CONTRACTS section 3.4. */
class EpisodeTest {

  private static final Instant NOW = Instant.parse("2026-11-11T20:30:00Z");

  @Test
  void openingTakesTheDetectionAsFirstAndLatest() {
    Detection d = detection(Severity.MEDIUM, 0);

    Alert episode = Alert.open(d);

    assertThat(episode.id())
        .isEqualTo(EpisodeIds.of(d.patientId(), d.type(), d.metric(), d.windowStart()));
    assertThat(episode.status()).isEqualTo(AlertStatus.OPEN);
    assertThat(episode.version()).isZero();
    assertThat(episode.occurrences()).isEqualTo(1);
    assertThat(episode.firstSeenAt()).isEqualTo(d.windowStart());
    assertThat(episode.lastSeenAt()).isEqualTo(d.windowEnd());
    assertThat(episode.lastDetectionId()).isEqualTo(d.id());
    assertThat(episode.resolvedAt()).isNull();
    assertThat(episode.parentAlertId()).isNull();
    assertThat(episode.isTopLevel()).isTrue();
  }

  @Test
  void newerWindowUpdatesTheSameEpisode() {
    Alert episode = Alert.open(detection(Severity.MEDIUM, 0));
    Detection next = detection(Severity.MEDIUM, 1);

    Absorption result = episode.absorb(next).orElseThrow();

    Alert updated = result.episode();
    assertThat(result.change()).isEqualTo(EpisodeChange.UPDATED);
    assertThat(updated.id()).isEqualTo(episode.id());
    assertThat(updated.occurrences()).isEqualTo(2);
    assertThat(updated.firstSeenAt()).isEqualTo(episode.firstSeenAt());
    assertThat(updated.lastSeenAt()).isEqualTo(next.windowEnd());
    assertThat(updated.windowStart()).isEqualTo(next.windowStart());
    assertThat(updated.windowEnd()).isEqualTo(next.windowEnd());
    assertThat(updated.detectedAt()).isEqualTo(next.detectedAt());
    assertThat(updated.lastDetectionId()).isEqualTo(next.id());
    assertThat(updated.message()).isEqualTo(next.message());
    assertThat(updated.status()).isEqualTo(AlertStatus.OPEN);
  }

  @Test
  void aWindowOlderThanTheLatestIsIgnored() {
    Alert episode = Alert.open(detection(Severity.MEDIUM, 5));

    assertThat(episode.absorb(detection(Severity.CRITICAL, 4))).isEmpty();
  }

  @Test
  void aReplayOfTheSameWindowWithoutRaiseIsIgnored() {
    Detection d = detection(Severity.HIGH, 0);
    Alert episode = Alert.open(d);

    assertThat(episode.absorb(d)).isEmpty();
    assertThat(episode.absorb(detection(Severity.LOW, 0))).isEmpty();
  }

  @Test
  void sameWindowWithHigherSeverityOnlyRaisesSeverity() {
    Alert episode = Alert.open(detection(Severity.MEDIUM, 0));
    Detection raised = detection(Severity.CRITICAL, 0);

    Absorption result = episode.absorb(raised).orElseThrow();

    assertThat(result.change()).isEqualTo(EpisodeChange.ESCALATED);
    assertThat(result.episode().severity()).isEqualTo(Severity.CRITICAL);
    assertThat(result.episode().occurrences()).isEqualTo(1);
    assertThat(result.episode().lastDetectionId()).isEqualTo(episode.lastDetectionId());
  }

  @Test
  void severityNeverLowers() {
    Alert episode = Alert.open(detection(Severity.CRITICAL, 0));

    Absorption result = episode.absorb(detection(Severity.LOW, 1)).orElseThrow();

    assertThat(result.change()).isEqualTo(EpisodeChange.UPDATED);
    assertThat(result.episode().severity()).isEqualTo(Severity.CRITICAL);
  }

  @Test
  void aRaiseIsAnEscalationAndKeepsCounting() {
    Alert episode = Alert.open(detection(Severity.MEDIUM, 0));

    Absorption result = episode.absorb(detection(Severity.HIGH, 1)).orElseThrow();

    assertThat(result.change()).isEqualTo(EpisodeChange.ESCALATED);
    assertThat(result.episode().severity()).isEqualTo(Severity.HIGH);
    assertThat(result.episode().occurrences()).isEqualTo(2);
  }

  @Test
  void updatesWhileAcknowledgedKeepTheAcknowledgementAndCount() {
    Alert acked = Alert.open(detection(Severity.MEDIUM, 0)).acknowledge("dr", "seen", NOW);

    Alert updated = acked.absorb(detection(Severity.MEDIUM, 1)).orElseThrow().episode();

    assertThat(updated.status()).isEqualTo(AlertStatus.ACKNOWLEDGED);
    assertThat(updated.occurrences()).isEqualTo(2);
    assertThat(updated.acknowledgedBy()).isEqualTo("dr");
  }

  @Test
  void escalationReopensAnAcknowledgedEpisodeButKeepsTheAuditFields() {
    Alert acked = Alert.open(detection(Severity.MEDIUM, 0)).acknowledge("dr", "seen", NOW);

    Absorption result = acked.absorb(detection(Severity.CRITICAL, 1)).orElseThrow();

    assertThat(result.change()).isEqualTo(EpisodeChange.ESCALATED);
    assertThat(result.episode().status()).isEqualTo(AlertStatus.OPEN);
    assertThat(result.episode().acknowledgedBy()).isEqualTo("dr");
    assertThat(result.episode().acknowledgedAt()).isEqualTo(NOW);
    assertThat(result.episode().acknowledgementNote()).isEqualTo("seen");
  }

  @Test
  void anEscalationInTheSameWindowAlsoReopens() {
    Alert acked = Alert.open(detection(Severity.MEDIUM, 0)).acknowledge("dr", null, NOW);

    Alert reopened = acked.absorb(detection(Severity.HIGH, 0)).orElseThrow().episode();

    assertThat(reopened.status()).isEqualTo(AlertStatus.OPEN);
  }

  @Test
  void aResolvedEpisodeAbsorbsNothing() {
    Alert resolved = TestAlerts.alert(Severity.LOW).withStatus(AlertStatus.RESOLVED);

    assertThat(resolved.absorb(detection(Severity.CRITICAL, 9))).isEmpty();
  }

  @Test
  void absorbDoesNotMutateTheOriginal() {
    Alert episode = Alert.open(detection(Severity.MEDIUM, 0));

    Optional<Absorption> result = episode.absorb(detection(Severity.HIGH, 1));

    assertThat(result).isPresent();
    assertThat(episode.occurrences()).isEqualTo(1);
    assertThat(episode.severity()).isEqualTo(Severity.MEDIUM);
  }

  @Test
  void detectionRequiresItsWindow() {
    assertThatThrownBy(
            () ->
                new Detection(
                    UUID.randomUUID(),
                    "P-1",
                    AlertType.NEWS2,
                    Severity.LOW,
                    null,
                    null,
                    null,
                    null,
                    null,
                    NOW,
                    NOW))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void acknowledgeOnlyMovesAnOpenEpisode() {
    Alert acked = TestAlerts.alert(Severity.HIGH).acknowledge("a", null, NOW);
    Alert resolved = TestAlerts.alert(Severity.HIGH).withStatus(AlertStatus.RESOLVED);

    assertThatThrownBy(() -> acked.acknowledge("b", null, NOW))
        .isInstanceOf(InvalidStatusTransitionException.class);
    assertThatThrownBy(() -> resolved.acknowledge("b", null, NOW))
        .isInstanceOf(InvalidStatusTransitionException.class);
  }

  @Test
  void resolveClosesAnOpenOrAcknowledgedEpisode() {
    Alert open = TestAlerts.alert(Severity.HIGH);
    Alert acked = open.acknowledge("dr", null, NOW);

    assertThat(open.resolve(NOW).status()).isEqualTo(AlertStatus.RESOLVED);
    assertThat(open.resolve(NOW).resolvedAt()).isEqualTo(NOW);
    assertThat(acked.resolve(NOW).acknowledgedBy()).isEqualTo("dr");
  }

  @Test
  void aResolvedEpisodeCannotBeResolvedAgain() {
    Alert resolved = TestAlerts.alert(Severity.HIGH).resolve(NOW);

    assertThatThrownBy(() -> resolved.resolve(NOW))
        .isInstanceOf(InvalidStatusTransitionException.class);
  }
}
