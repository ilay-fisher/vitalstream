package io.vitalstream.clinical.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.vitalstream.clinical.application.AlertFilter;
import io.vitalstream.clinical.application.AlertView;
import io.vitalstream.clinical.application.ResolvedEpisode;
import io.vitalstream.clinical.domain.Alert;
import io.vitalstream.clinical.domain.AlertStatus;
import io.vitalstream.clinical.domain.AlertType;
import io.vitalstream.clinical.domain.Detection;
import io.vitalstream.clinical.domain.Severity;
import io.vitalstream.clinical.infrastructure.jdbc.JdbcAlertRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The SQL rules behind the episode lifecycle, against a real Postgres with the real migrations. */
@Tag("integration")
class JdbcAlertRepositoryIT {

  private static final Instant T0 = Instant.parse("2026-11-11T20:15:00Z");

  private JdbcAlertRepository repo;

  @BeforeEach
  void freshDatabase() {
    var source = PostgresSupport.freshDatabase();
    PostgresSupport.migrate(source, null);
    repo = new JdbcAlertRepository(PostgresSupport.client(source));
  }

  private static Detection detection(
      String patient, AlertType type, Severity severity, String metric, int minute) {
    Instant start = T0.plusSeconds(60L * minute);
    return new Detection(
        UUID.randomUUID(),
        patient,
        type,
        severity,
        metric,
        metric == null ? null : 101.5,
        type == AlertType.NEWS2 ? 7.0 : null,
        "message " + minute,
        start,
        start.plusSeconds(60),
        start.plusSeconds(62));
  }

  private Alert insert(
      String patient, AlertType type, Severity severity, String metric, int minute) {
    Alert episode = Alert.open(detection(patient, type, severity, metric, minute));
    assertThat(repo.insert(episode)).isTrue();
    return episode;
  }

  @Test
  void insertedEpisodeRoundTripsEveryField() {
    Detection d = detection("P-1", AlertType.NEWS2, Severity.CRITICAL, null, 0);
    Alert episode = Alert.open(d);

    assertThat(repo.insert(episode)).isTrue();

    Alert stored = repo.findById(episode.id()).orElseThrow();
    assertThat(stored).isEqualTo(episode);
    assertThat(stored.firstSeenAt()).isEqualTo(d.windowStart());
    assertThat(stored.lastSeenAt()).isEqualTo(d.windowEnd());
    assertThat(stored.lastDetectionId()).isEqualTo(d.id());
    assertThat(stored.score()).isEqualTo(7.0);
  }

  @Test
  void findActiveMatchesTheKeyWithNullMetricAndIgnoresResolvedEpisodes() {
    Alert news2 = insert("P-2", AlertType.NEWS2, Severity.HIGH, null, 0);
    insert("P-2", AlertType.THRESHOLD, Severity.HIGH, "SPO2", 0);

    assertThat(repo.findActive("P-2", AlertType.NEWS2, null)).map(Alert::id).contains(news2.id());
    assertThat(repo.findActive("P-2", AlertType.NEWS2, "")).map(Alert::id).contains(news2.id());
    assertThat(repo.findActive("P-2", AlertType.THRESHOLD, "HEART_RATE")).isEmpty();
    assertThat(repo.findActive("P-9", AlertType.NEWS2, null)).isEmpty();

    repo.resolveStale(T0.plusSeconds(3600), T0.plusSeconds(3600));

    assertThat(repo.findActive("P-2", AlertType.NEWS2, null)).isEmpty();
  }

  @Test
  void theUniqueIndexAllowsOneNonResolvedEpisodePerKeyAndFreesTheKeyOnResolve() {
    Alert first = insert("P-3", AlertType.THRESHOLD, Severity.HIGH, "SPO2", 0);

    assertThat(repo.insert(first)).as("same id").isFalse();
    assertThat(
            repo.insert(
                Alert.open(detection("P-3", AlertType.THRESHOLD, Severity.HIGH, "SPO2", 5))))
        .as("same key, other window")
        .isFalse();
    assertThat(
            repo.insert(
                Alert.open(detection("P-3", AlertType.THRESHOLD, Severity.HIGH, "HEART_RATE", 5))))
        .as("other metric")
        .isTrue();

    repo.resolveStale(T0.plusSeconds(3600), T0.plusSeconds(3600));

    assertThat(
            repo.insert(
                Alert.open(detection("P-3", AlertType.THRESHOLD, Severity.HIGH, "SPO2", 90))))
        .as("key is free after resolve")
        .isTrue();
  }

  @Test
  void updateIsCompareAndSetOnTheVersionAndBumpsIt() {
    Alert episode = insert("P-4", AlertType.THRESHOLD, Severity.MEDIUM, "HEART_RATE", 0);
    Detection next = detection("P-4", AlertType.THRESHOLD, Severity.HIGH, "HEART_RATE", 1);
    Alert updated = episode.absorb(next).orElseThrow().episode();

    assertThat(repo.update(updated, 7)).isFalse();
    assertThat(repo.update(updated, 0)).isTrue();
    assertThat(repo.update(updated, 0)).isFalse();

    Alert stored = repo.findById(episode.id()).orElseThrow();
    assertThat(stored.version()).isEqualTo(1);
    assertThat(stored.severity()).isEqualTo(Severity.HIGH);
    assertThat(stored.occurrences()).isEqualTo(2);
    assertThat(stored.lastSeenAt()).isEqualTo(next.windowEnd());
    assertThat(stored.windowStart()).isEqualTo(next.windowStart());
    assertThat(stored.lastDetectionId()).isEqualTo(next.id());
    assertThat(stored.message()).isEqualTo("message 1");
    assertThat(stored.firstSeenAt()).isEqualTo(episode.firstSeenAt());
  }

  @Test
  void updateWritesParentAndReopenedStatusButKeepsAcknowledgementAudit() {
    Alert parent = insert("P-5", AlertType.NEWS2, Severity.HIGH, null, 0);
    Alert child = insert("P-5", AlertType.THRESHOLD, Severity.MEDIUM, "HEART_RATE", 0);
    Alert acked = child.acknowledge("dr", "seen", T0.plusSeconds(100));
    assertThat(repo.updateAcknowledgement(acked, 0)).isTrue();
    Alert current = repo.findById(child.id()).orElseThrow();
    Alert escalated =
        current
            .absorb(detection("P-5", AlertType.THRESHOLD, Severity.HIGH, "HEART_RATE", 1))
            .orElseThrow()
            .episode()
            .withParent(parent.id());

    assertThat(repo.update(escalated, current.version())).isTrue();

    Alert stored = repo.findById(child.id()).orElseThrow();
    assertThat(stored.status()).isEqualTo(AlertStatus.OPEN);
    assertThat(stored.parentAlertId()).isEqualTo(parent.id());
    assertThat(stored.acknowledgedBy()).isEqualTo("dr");
    assertThat(stored.acknowledgementNote()).isEqualTo("seen");
    assertThat(repo.findById(parent.id()).orElseThrow().childCount()).isEqualTo(1);
  }

  @Test
  void acknowledgementIsCompareAndSetToo() {
    Alert episode = insert("P-6", AlertType.THRESHOLD, Severity.HIGH, "SPO2", 0);
    Alert acked = episode.acknowledge("dr", "n", T0.plusSeconds(90));

    assertThat(repo.updateAcknowledgement(acked, 3)).isFalse();
    assertThat(repo.updateAcknowledgement(acked, 0)).isTrue();

    Alert stored = repo.findById(episode.id()).orElseThrow();
    assertThat(stored.status()).isEqualTo(AlertStatus.ACKNOWLEDGED);
    assertThat(stored.acknowledgedAt()).isEqualTo(T0.plusSeconds(90));
    assertThat(stored.version()).isEqualTo(1);
  }

  @Test
  void linkAndUnlinkChildrenMoveOnlyTheRightEpisodesAndBumpTheirVersions() {
    Alert parent = insert("P-7", AlertType.NEWS2, Severity.HIGH, null, 0);
    Alert low = insert("P-7", AlertType.THRESHOLD, Severity.MEDIUM, "HEART_RATE", 0);
    Alert equal = insert("P-7", AlertType.TREND_ZSCORE, Severity.HIGH, "HEART_RATE", 0);
    Alert outranks = insert("P-7", AlertType.ML_ANOMALY, Severity.CRITICAL, null, 0);
    Alert elsewhere = insert("P-8", AlertType.THRESHOLD, Severity.LOW, "HEART_RATE", 0);

    List<UUID> linked = repo.linkChildren("P-7", parent.id(), Severity.HIGH);

    assertThat(linked).containsExactlyInAnyOrder(low.id(), equal.id());
    assertThat(repo.findById(low.id()).orElseThrow().parentAlertId()).isEqualTo(parent.id());
    assertThat(repo.findById(low.id()).orElseThrow().version()).isEqualTo(1);
    assertThat(repo.findById(outranks.id()).orElseThrow().parentAlertId()).isNull();
    assertThat(repo.findById(elsewhere.id()).orElseThrow().parentAlertId()).isNull();
    assertThat(repo.findById(parent.id()).orElseThrow().childCount()).isEqualTo(2);
    assertThat(repo.linkChildren("P-7", parent.id(), Severity.HIGH)).as("idempotent").isEmpty();

    List<UUID> unlinked = repo.unlinkChildren(List.of(parent.id()));

    assertThat(unlinked).containsExactlyInAnyOrder(low.id(), equal.id());
    assertThat(repo.findById(low.id()).orElseThrow().parentAlertId()).isNull();
    assertThat(repo.findById(low.id()).orElseThrow().version()).isEqualTo(2);
    assertThat(repo.unlinkChildren(List.<UUID>of())).isEmpty();
    assertThat(repo.unlinkChildren(List.of(UUID.randomUUID()))).isEmpty();
  }

  @Test
  void childCountIgnoresResolvedChildrenAndTouchBumpsTheVersion() {
    Alert parent = insert("P-10", AlertType.NEWS2, Severity.HIGH, null, 30);
    insert("P-10", AlertType.THRESHOLD, Severity.LOW, "SPO2", 0);
    insert("P-10", AlertType.THRESHOLD, Severity.LOW, "HEART_RATE", 30);
    repo.linkChildren("P-10", parent.id(), Severity.HIGH);

    // the SPO2 child was last seen at minute 1: stale. Parent and the other child are fresh.
    List<ResolvedEpisode> resolved =
        repo.resolveStale(T0.plusSeconds(60 * 10), T0.plusSeconds(60 * 11));

    assertThat(resolved).hasSize(1);
    assertThat(resolved.getFirst().parentAlertId()).isEqualTo(parent.id());
    assertThat(repo.findById(parent.id()).orElseThrow().childCount()).isEqualTo(1);
    long before = repo.findById(parent.id()).orElseThrow().version();
    repo.touch(parent.id());
    assertThat(repo.findById(parent.id()).orElseThrow().version()).isEqualTo(before + 1);
  }

  @Test
  void resolveStaleResolvesOnlySilentEpisodesOnceAndKeepsAcknowledgementAudit() {
    Alert stale = insert("P-11", AlertType.THRESHOLD, Severity.HIGH, "SPO2", 0);
    Alert fresh = insert("P-11", AlertType.THRESHOLD, Severity.HIGH, "HEART_RATE", 30);
    repo.updateAcknowledgement(stale.acknowledge("dr", null, T0.plusSeconds(20)), 0);
    Instant now = T0.plusSeconds(60 * 10);

    List<ResolvedEpisode> first = repo.resolveStale(T0.plusSeconds(60 * 5), now);
    List<ResolvedEpisode> second = repo.resolveStale(T0.plusSeconds(60 * 5), now);

    assertThat(first).extracting(ResolvedEpisode::id).containsExactly(stale.id());
    assertThat(first.getFirst().type()).isEqualTo(AlertType.THRESHOLD);
    assertThat(second).isEmpty();
    Alert resolved = repo.findById(stale.id()).orElseThrow();
    assertThat(resolved.status()).isEqualTo(AlertStatus.RESOLVED);
    assertThat(resolved.resolvedAt()).isEqualTo(now);
    assertThat(resolved.acknowledgedBy()).isEqualTo("dr");
    assertThat(resolved.version()).isEqualTo(2);
    assertThat(repo.findById(fresh.id()).orElseThrow().status()).isEqualTo(AlertStatus.OPEN);
  }

  @Test
  void concurrentResolversResolveEachEpisodeExactlyOnce() throws Exception {
    List<Alert> episodes = new ArrayList<>();
    for (int i = 0; i < 20; i++) {
      episodes.add(insert("P-12", AlertType.THRESHOLD, Severity.LOW, "M" + i, 0));
    }
    CyclicBarrier start = new CyclicBarrier(4);
    List<CompletableFuture<List<ResolvedEpisode>>> runs = new ArrayList<>();
    for (int i = 0; i < 4; i++) {
      runs.add(
          CompletableFuture.supplyAsync(() -> {
            try {
              start.await();
            } catch (Exception e) {
              throw new IllegalStateException(e);
            }
            return repo.resolveStale(T0.plusSeconds(3600), T0.plusSeconds(3600));
          }));
    }

    List<UUID> all =
        runs.stream()
            .map(CompletableFuture::join)
            .flatMap(Collection::stream)
            .map(ResolvedEpisode::id)
            .toList();

    assertThat(all).hasSize(20).doesNotHaveDuplicates();
    assertThat(repo.findById(episodes.getFirst().id()).orElseThrow().version()).isEqualTo(1);
  }

  @Test
  void searchFiltersAndSortsByLastSeenDescending() {
    Alert parent = insert("P-13", AlertType.NEWS2, Severity.HIGH, null, 3);
    Alert child = insert("P-13", AlertType.THRESHOLD, Severity.MEDIUM, "HEART_RATE", 2);
    Alert other = insert("P-13", AlertType.TREND_ZSCORE, Severity.CRITICAL, "SPO2", 1);
    repo.linkChildren("P-13", parent.id(), Severity.HIGH);
    repo.updateAcknowledgement(other.acknowledge("dr", null, T0), 0);

    assertThat(ids(filter("P-13", null, List.of(), null, false, null, 0, 10)))
        .containsExactly(parent.id(), child.id(), other.id());
    assertThat(ids(filter("P-13", null, List.of(), null, true, null, 0, 10)))
        .containsExactly(parent.id(), other.id());
    assertThat(ids(filter("P-13", null, List.of(), null, false, parent.id(), 0, 10)))
        .containsExactly(child.id());
    assertThat(
            ids(
                filter(
                    "P-13",
                    null,
                    List.of(AlertStatus.OPEN, AlertStatus.ACKNOWLEDGED),
                    null,
                    false,
                    null,
                    0,
                    10)))
        .hasSize(3);
    assertThat(
            ids(filter("P-13", null, List.of(AlertStatus.ACKNOWLEDGED), null, false, null, 0, 10)))
        .containsExactly(other.id());
    assertThat(ids(filter("P-13", null, List.of(), AlertType.THRESHOLD, false, null, 0, 10)))
        .containsExactly(child.id());
    assertThat(ids(filter("P-13", Severity.CRITICAL, List.of(), null, false, null, 0, 10)))
        .containsExactly(other.id());
    var page = repo.search(filter("P-13", null, List.of(), null, false, null, 1, 2));
    assertThat(page.items()).extracting(AlertView::alertId).containsExactly(other.id());
    assertThat(page.totalElements()).isEqualTo(3);
    assertThat(page.items().getFirst().childCount()).isZero();
    assertThat(
            repo.search(filter("P-13", null, List.of(), null, false, null, 0, 1))
                .items()
                .getFirst()
                .childCount())
        .isEqualTo(1);
  }

  private static AlertFilter filter(
      String patient,
      Severity severity,
      List<AlertStatus> statuses,
      AlertType type,
      boolean topLevel,
      UUID parent,
      int page,
      int size) {
    return new AlertFilter(patient, severity, statuses, type, topLevel, parent, null, page, size);
  }

  private List<UUID> ids(AlertFilter filter) {
    return repo.search(filter).items().stream().map(AlertView::alertId).toList();
  }

  @Test
  void openCountsAreTopLevelOpenOrAcknowledgedEpisodes() {
    Alert parent = insert("P-14", AlertType.NEWS2, Severity.HIGH, null, 0);
    Alert acked = insert("P-14", AlertType.THRESHOLD, Severity.CRITICAL, "SPO2", 0);
    insert("P-14", AlertType.THRESHOLD, Severity.LOW, "HEART_RATE", 0);
    insert("P-15", AlertType.NEWS2, Severity.LOW, null, 0);
    repo.updateAcknowledgement(acked.acknowledge("dr", null, T0), 0);
    repo.linkChildren("P-14", parent.id(), Severity.MEDIUM);
    repo.resolveStale(T0.plusSeconds(60 * 20), T0.plusSeconds(60 * 20));
    insert("P-14", AlertType.ML_ANOMALY, Severity.LOW, null, 30);

    assertThat(repo.openCount("P-14")).isEqualTo(1L);
    assertThat(repo.openCountsByPatient()).containsEntry("P-14", 1L).doesNotContainKey("P-15");
  }

  @Test
  void openCountIncludesAcknowledgedAndExcludesChildren() {
    Alert parent = insert("P-16", AlertType.NEWS2, Severity.HIGH, null, 0);
    Alert acked = insert("P-16", AlertType.THRESHOLD, Severity.CRITICAL, "SPO2", 0);
    insert("P-16", AlertType.THRESHOLD, Severity.LOW, "HEART_RATE", 0);
    repo.updateAcknowledgement(acked.acknowledge("dr", null, T0), 0);
    repo.linkChildren("P-16", parent.id(), Severity.MEDIUM);

    // NEWS2 (top-level OPEN) + the ACKNOWLEDGED critical that outranks it; the LOW one is a child
    assertThat(repo.openCount("P-16")).isEqualTo(2L);
  }
}
