package io.vitalstream.clinical.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vitalstream.clinical.domain.AlertNotFoundException;
import io.vitalstream.clinical.domain.Patient;
import io.vitalstream.clinical.domain.PatientNotFoundException;
import io.vitalstream.clinical.domain.Severity;
import io.vitalstream.clinical.domain.TestAlerts;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class QueryServicesTest {

  private final AlertRepository alerts = mock(AlertRepository.class);
  private final AlertSearch search = mock(AlertSearch.class);
  private final PatientRepository patients = mock(PatientRepository.class);
  private final LatestVitalsStore latest = mock(LatestVitalsStore.class);

  @Test
  void listWithoutTextQueryUsesPostgres() {
    AlertFilter filter = new AlertFilter("P-0001", Severity.HIGH, null, null, 0, 20);
    PageResult<AlertView> page = new PageResult<>(List.of(), 0, 20, 0);
    when(alerts.search(filter)).thenReturn(page);

    assertThat(new AlertQueryService(alerts, search).list(filter)).isSameAs(page);
    verify(search, never()).search(any());
  }

  @Test
  void listWithTextQueryUsesElasticsearch() {
    AlertFilter filter = new AlertFilter(null, null, null, "heart", 1, 10);
    PageResult<AlertView> page = new PageResult<>(List.of(), 1, 10, 0);
    when(search.search(filter)).thenReturn(page);

    assertThat(new AlertQueryService(alerts, search).list(filter)).isSameAs(page);
    verify(alerts, never()).search(any());
  }

  @Test
  void blankTextQueryFallsBackToPostgres() {
    AlertFilter filter = new AlertFilter(null, null, null, "  ", 0, 10);
    when(alerts.search(filter)).thenReturn(new PageResult<>(List.of(), 0, 10, 0));

    new AlertQueryService(alerts, search).list(filter);

    verify(alerts).search(filter);
    assertThat(filter.offset()).isZero();
  }

  @Test
  void getUnknownAlertThrowsNotFound() {
    UUID id = UUID.randomUUID();
    when(alerts.findById(id)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> new AlertQueryService(alerts, search).get(id))
        .isInstanceOf(AlertNotFoundException.class);
  }

  @Test
  void getReturnsView() {
    var alert = TestAlerts.alert(Severity.LOW);
    when(alerts.findById(alert.id())).thenReturn(Optional.of(alert));

    assertThat(new AlertQueryService(alerts, search).get(alert.id()).alertId())
        .isEqualTo(alert.id());
  }

  @Test
  void pageResultComputesTotalPages() {
    assertThat(new PageResult<>(List.of(), 0, 20, 41).totalPages()).isEqualTo(3);
    assertThat(new PageResult<>(List.of(), 0, 0, 5).totalPages()).isZero();
  }

  @Test
  void patientListMergesLatestVitalsAndOpenCounts() {
    when(patients.findAll())
        .thenReturn(
            List.of(
                new Patient("P-0001", "A", "1", "ICU"), new Patient("P-0002", "B", "2", "ICU")));
    when(alerts.openCountsByPatient()).thenReturn(Map.of("P-0001", 3L));
    LatestVital hr = new LatestVital(80.0, "/min", Instant.parse("2026-11-11T20:00:00Z"));
    when(latest.latest("P-0001")).thenReturn(Map.of("HEART_RATE", hr));
    when(latest.latest("P-0002")).thenReturn(Map.of());

    List<PatientView> views = new PatientQueryService(patients, latest, alerts).list();

    assertThat(views).extracting(PatientView::openAlerts).containsExactly(3L, 0L);
    assertThat(views.getFirst().latestVitals()).containsEntry("HEART_RATE", hr);
  }

  @Test
  void patientGetAndRequireExistsFailForUnknownId() {
    when(patients.findById("P-9")).thenReturn(Optional.empty());
    PatientQueryService service = new PatientQueryService(patients, latest, alerts);

    assertThatThrownBy(() -> service.get("P-9")).isInstanceOf(PatientNotFoundException.class);
    assertThatThrownBy(() -> service.requireExists("P-9"))
        .isInstanceOf(PatientNotFoundException.class);
  }

  @Test
  void patientGetIncludesOpenCount() {
    when(patients.findById("P-1")).thenReturn(Optional.of(new Patient("P-1", "A", "1", "W")));
    when(alerts.openCount("P-1")).thenReturn(2L);
    when(latest.latest("P-1")).thenReturn(Map.of());

    assertThat(new PatientQueryService(patients, latest, alerts).get("P-1").openAlerts())
        .isEqualTo(2L);
  }

  @Test
  void vitalsHistoryIsServedFromCacheOnHitAndFromCassandraOnMiss() {
    VitalsHistoryReader reader = mock(VitalsHistoryReader.class);
    VitalsCache cache = mock(VitalsCache.class);
    PatientQueryService patientService = mock(PatientQueryService.class);
    SimpleMeterRegistry meters = new SimpleMeterRegistry();
    VitalsQueryService service = new VitalsQueryService(reader, cache, patientService, meters);
    VitalsQuery query =
        new VitalsQuery(
            "P-1",
            null,
            Instant.parse("2026-11-11T19:00:00Z"),
            Instant.parse("2026-11-11T20:00:00Z"),
            VitalsQuery.Resolution.RAW);
    VitalPoint point =
        VitalPoint.raw(Instant.parse("2026-11-11T19:30:00Z"), "SPO2", 97, "%", "D-1");
    when(cache.get(query)).thenReturn(Optional.empty());
    when(reader.read(query)).thenReturn(List.of(point));

    VitalsHistory miss = service.history(query);

    assertThat(miss.points()).containsExactly(point);
    verify(cache).put(query, miss);
    when(cache.get(query)).thenReturn(Optional.of(miss));

    assertThat(service.history(query)).isSameAs(miss);
    verify(reader).read(query);
    assertThat(meters.counter("cache.requests", "cache", "vitals", "result", "hit").count())
        .isEqualTo(1.0);
    assertThat(meters.counter("cache.requests", "cache", "vitals", "result", "miss").count())
        .isEqualTo(1.0);
  }

  @Test
  void vitalPointFactoriesFillOnlyRelevantFields() {
    VitalPoint raw = VitalPoint.raw(Instant.EPOCH, "SPO2", 97, "%", "D");
    VitalPoint minute = VitalPoint.minute(Instant.EPOCH, "SPO2", 90, 99, 95, 12);

    assertThat(raw.min()).isNull();
    assertThat(minute.value()).isNull();
    assertThat(minute.count()).isEqualTo(12);
  }
}
