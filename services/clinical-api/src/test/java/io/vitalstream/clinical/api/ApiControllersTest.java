package io.vitalstream.clinical.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.vitalstream.clinical.application.AckActor;
import io.vitalstream.clinical.application.AlertAckService;
import io.vitalstream.clinical.application.AlertFilter;
import io.vitalstream.clinical.application.AlertQueryService;
import io.vitalstream.clinical.application.AlertView;
import io.vitalstream.clinical.application.LatestVital;
import io.vitalstream.clinical.application.PageResult;
import io.vitalstream.clinical.application.PatientQueryService;
import io.vitalstream.clinical.application.PatientView;
import io.vitalstream.clinical.application.VitalPoint;
import io.vitalstream.clinical.application.VitalsHistory;
import io.vitalstream.clinical.application.VitalsQuery;
import io.vitalstream.clinical.application.VitalsQueryService;
import io.vitalstream.clinical.domain.AlertNotFoundException;
import io.vitalstream.clinical.domain.AlertStatus;
import io.vitalstream.clinical.domain.ConcurrentAlertUpdateException;
import io.vitalstream.clinical.domain.InvalidStatusTransitionException;
import io.vitalstream.clinical.domain.PatientNotFoundException;
import io.vitalstream.clinical.domain.Severity;
import io.vitalstream.clinical.domain.TestAlerts;
import io.vitalstream.clinical.infrastructure.security.SecurityConfig;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcBuilderCustomizer;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.RestClientException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@WebMvcTest({AlertController.class, PatientController.class})
@Import(SecurityConfig.class)
class ApiControllersTest {

  private static final Instant NOW = Instant.parse("2026-11-11T20:00:00Z");

  @TestConfiguration
  static class FixedClock {
    @Bean
    Clock clock() {
      return Clock.fixed(NOW, ZoneOffset.UTC);
    }

    /** Every request in this class is made by a signed-in clinician; auth rules live elsewhere. */
    @Bean
    MockMvcBuilderCustomizer signedInClinician() {
      return builder ->
          builder.defaultRequest(
              get("/")
                  .with(
                      jwt()
                          .jwt(j -> j.subject("dr.cohen").claim("name", "Dr. Cohen"))
                          .authorities(new SimpleGrantedAuthority("ROLE_CLINICIAN"))));
    }
  }

  @Autowired MockMvc mvc;
  @MockitoBean AlertQueryService alertQueries;
  @MockitoBean AlertAckService acks;
  @MockitoBean SseAlertHub hub;
  @MockitoBean PatientQueryService patients;
  @MockitoBean VitalsQueryService vitals;

  @Test
  void listPassesFiltersAndPaginationAndReturnsPage() throws Exception {
    AlertView view = AlertView.from(TestAlerts.alert(Severity.HIGH));
    when(alertQueries.list(any())).thenReturn(new PageResult<>(List.of(view), 1, 10, 31));

    mvc.perform(
            get("/api/v1/alerts")
                .param("patientId", "P-0001")
                .param("severity", "HIGH")
                .param("status", "OPEN")
                .param("q", "heart")
                .param("page", "1")
                .param("size", "10"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].alertId").value(view.alertId().toString()))
        .andExpect(jsonPath("$.totalElements").value(31))
        .andExpect(jsonPath("$.totalPages").value(4));

    ArgumentCaptor<AlertFilter> filter = ArgumentCaptor.forClass(AlertFilter.class);
    verify(alertQueries).list(filter.capture());
    org.assertj.core.api.Assertions.assertThat(filter.getValue())
        .isEqualTo(new AlertFilter("P-0001", Severity.HIGH, AlertStatus.OPEN, "heart", 1, 10));
  }

  @Test
  void listRejectsOversizedPageWithProblemJson() throws Exception {
    mvc.perform(get("/api/v1/alerts").param("size", "1000"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
  }

  @Test
  void listRejectsUnknownSeverity() throws Exception {
    mvc.perform(get("/api/v1/alerts").param("severity", "SCARY"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.status").value(400));
  }

  @Test
  void unknownAlertIsProblemJson404WithCorrelationId() throws Exception {
    UUID id = UUID.randomUUID();
    when(alertQueries.get(id)).thenThrow(new AlertNotFoundException(id));

    mvc.perform(get("/api/v1/alerts/" + id).header("X-Correlation-Id", "corr-123"))
        .andExpect(status().isNotFound())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(header().string("X-Correlation-Id", "corr-123"))
        .andExpect(jsonPath("$.title").value("Resource not found"))
        .andExpect(jsonPath("$.correlationId").value("corr-123"));
  }

  @Test
  void correlationIdIsGeneratedWhenMissingOrUnsafe() throws Exception {
    when(alertQueries.get(any())).thenReturn(AlertView.from(TestAlerts.alert(Severity.LOW)));
    UUID id = UUID.randomUUID();

    mvc.perform(get("/api/v1/alerts/" + id)).andExpect(header().exists("X-Correlation-Id"));
    mvc.perform(get("/api/v1/alerts/" + id).header("X-Correlation-Id", "bad id\twith spaces"))
        .andExpect(
            header()
                .string("X-Correlation-Id", org.hamcrest.Matchers.matchesPattern("[0-9a-f-]{36}")));
  }

  @Test
  void ackReturnsUpdatedAlert() throws Exception {
    UUID id = UUID.randomUUID();
    AlertView acked =
        AlertView.from(TestAlerts.alert(Severity.HIGH).acknowledge("dr.cohen", "ok", NOW));
    when(acks.acknowledge(id, new AckActor("dr.cohen", "Dr. Cohen"), "ok")).thenReturn(acked);

    mvc.perform(
            post("/api/v1/alerts/" + id + "/ack")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"note\":\"ok\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ACKNOWLEDGED"))
        .andExpect(jsonPath("$.acknowledgedBy").value("dr.cohen"));
  }

  @Test
  void ackWithMalformedBodyIsBadRequest() throws Exception {
    mvc.perform(
            post("/api/v1/alerts/" + UUID.randomUUID() + "/ack")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{not json"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
  }

  @Test
  void ackConflictsMapTo409() throws Exception {
    UUID id = UUID.randomUUID();
    when(acks.acknowledge(eq(id), any(), any()))
        .thenThrow(
            new InvalidStatusTransitionException(
                AlertStatus.ACKNOWLEDGED, AlertStatus.ACKNOWLEDGED))
        .thenThrow(new ConcurrentAlertUpdateException(id));

    for (int i = 0; i < 2; i++) {
      mvc.perform(
              post("/api/v1/alerts/" + id + "/ack")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"note\":\"dr\"}"))
          .andExpect(status().isConflict())
          .andExpect(jsonPath("$.title").value("Conflict"));
    }
  }

  @Test
  void streamOpensServerSentEvents() throws Exception {
    when(hub.register()).thenReturn(new SseEmitter());

    mvc.perform(get("/api/v1/alerts/stream").accept(MediaType.TEXT_EVENT_STREAM))
        .andExpect(request().asyncStarted());
  }

  @Test
  void patientListAndDetail() throws Exception {
    PatientView view =
        new PatientView(
            "P-0001",
            "Dana",
            "B1",
            "ICU",
            Map.of("HEART_RATE", new LatestVital(88.0, "/min", NOW)),
            2);
    when(patients.list()).thenReturn(List.of(view));
    when(patients.get("P-0001")).thenReturn(view);
    when(patients.get("P-9")).thenThrow(new PatientNotFoundException("P-9"));

    mvc.perform(get("/api/v1/patients"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].latestVitals.HEART_RATE.value").value(88.0))
        .andExpect(jsonPath("$[0].openAlerts").value(2));
    mvc.perform(get("/api/v1/patients/P-0001")).andExpect(status().isOk());
    mvc.perform(get("/api/v1/patients/P-9")).andExpect(status().isNotFound());
  }

  @Test
  void vitalsDefaultsToLastHourRawForAllMetrics() throws Exception {
    when(vitals.history(any()))
        .thenReturn(
            new VitalsHistory(
                "P-0001",
                VitalsQuery.Resolution.RAW,
                NOW.minusSeconds(3600),
                NOW,
                List.of(VitalPoint.raw(NOW, "SPO2", 97, "%", "DEV-ICU-0001"))));

    mvc.perform(get("/api/v1/patients/P-0001/vitals"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.points[0].metric").value("SPO2"))
        .andExpect(jsonPath("$.points[0].observedAt").value("2026-11-11T20:00:00Z"))
        .andExpect(jsonPath("$.points[0].timestamp").doesNotExist())
        .andExpect(jsonPath("$.points[0].min").doesNotExist());

    ArgumentCaptor<VitalsQuery> query = ArgumentCaptor.forClass(VitalsQuery.class);
    verify(vitals).history(query.capture());
    org.assertj.core.api.Assertions.assertThat(query.getValue())
        .isEqualTo(
            new VitalsQuery(
                "P-0001", null, NOW.minusSeconds(3600), NOW, VitalsQuery.Resolution.RAW));
  }

  @Test
  void vitalsAcceptsMetricRangeAndMinuteResolution() throws Exception {
    when(vitals.history(any()))
        .thenReturn(new VitalsHistory("P-1", VitalsQuery.Resolution.MINUTE, NOW, NOW, List.of()));

    mvc.perform(
            get("/api/v1/patients/P-1/vitals")
                .param("metric", "HEART_RATE")
                .param("from", "2026-11-11T19:00:00Z")
                .param("to", "2026-11-11T19:30:00Z")
                .param("resolution", "minute"))
        .andExpect(status().isOk());

    ArgumentCaptor<VitalsQuery> query = ArgumentCaptor.forClass(VitalsQuery.class);
    verify(vitals).history(query.capture());
    org.assertj.core.api.Assertions.assertThat(query.getValue().resolution())
        .isEqualTo(VitalsQuery.Resolution.MINUTE);
  }

  @Test
  void vitalsRejectsBadResolutionInvertedAndOversizedRanges() throws Exception {
    mvc.perform(get("/api/v1/patients/P-1/vitals").param("resolution", "hourly"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.title").value("Invalid request"));
    mvc.perform(
            get("/api/v1/patients/P-1/vitals")
                .param("from", "2026-11-11T19:30:00Z")
                .param("to", "2026-11-11T19:00:00Z"))
        .andExpect(status().isBadRequest());
    mvc.perform(
            get("/api/v1/patients/P-1/vitals")
                .param("from", "2026-10-01T00:00:00Z")
                .param("to", "2026-11-01T00:00:00Z"))
        .andExpect(status().isBadRequest());
    mvc.perform(get("/api/v1/patients/P-1/vitals").param("metric", "PULSE"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void backingStoreFailureIs503AndUnexpectedErrorIs500WithoutLeakingDetails() throws Exception {
    when(alertQueries.list(any())).thenThrow(new RestClientException("es down: 10.0.0.5"));
    when(patients.list()).thenThrow(new IllegalStateException("secret internals"));

    mvc.perform(get("/api/v1/alerts").param("q", "x"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.detail").value("A backing store is temporarily unavailable"));
    mvc.perform(get("/api/v1/patients"))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.detail").value("Unexpected server error"));
  }

  @Test
  void foreignHostHeaderIsRejectedBeforeReachingControllers() throws Exception {
    mvc.perform(get("/api/v1/patients").header("Host", "evil.example.com"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
  }
}
