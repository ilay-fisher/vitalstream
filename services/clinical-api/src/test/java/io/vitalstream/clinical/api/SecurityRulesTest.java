package io.vitalstream.clinical.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.vitalstream.clinical.application.AckActor;
import io.vitalstream.clinical.application.AlertAckService;
import io.vitalstream.clinical.application.AlertQueryService;
import io.vitalstream.clinical.application.AlertView;
import io.vitalstream.clinical.application.PatientQueryService;
import io.vitalstream.clinical.application.VitalsQueryService;
import io.vitalstream.clinical.domain.Severity;
import io.vitalstream.clinical.domain.TestAlerts;
import io.vitalstream.clinical.infrastructure.config.ClinicalProperties;
import io.vitalstream.clinical.infrastructure.security.SecurityConfig;
import io.vitalstream.clinical.infrastructure.security.TestTokens;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** CONTRACTS section 4.2: who may call what, and the shape of the 401 and 403 answers. */
@WebMvcTest({AlertController.class, PatientController.class, DevTokenController.class})
@Import(SecurityConfig.class)
@EnableConfigurationProperties(ClinicalProperties.class)
class SecurityRulesTest {

  private static final String ACK = "/api/v1/alerts/" + UUID.randomUUID() + "/ack";

  @TestConfiguration
  static class Beans {
    @Bean
    Clock clock() {
      return Clock.systemUTC();
    }
  }

  @Autowired MockMvc mvc;
  @MockitoBean AlertQueryService alertQueries;
  @MockitoBean AlertAckService acks;
  @MockitoBean SseAlertHub hub;
  @MockitoBean PatientQueryService patients;
  @MockitoBean VitalsQueryService vitals;

  private static RequestPostProcessor as(String sub, String name, String... roles) {
    SimpleGrantedAuthority[] authorities =
        java.util.Arrays.stream(roles)
            .map(r -> new SimpleGrantedAuthority("ROLE_" + r))
            .toArray(SimpleGrantedAuthority[]::new);
    return jwt()
        .jwt(
            j -> {
              j.subject(sub);
              if (name != null) {
                j.claim("name", name);
              }
            })
        .authorities(authorities);
  }

  private static String bearer(String token) {
    return "Bearer " + token;
  }

  // ---- 401 -----------------------------------------------------------------------------------

  @Test
  void requestWithoutTokenIs401ProblemJsonWithBearerChallenge() throws Exception {
    mvc.perform(get("/api/v1/patients").header("X-Correlation-Id", "corr-401"))
        .andExpect(status().isUnauthorized())
        .andExpect(header().string("WWW-Authenticate", org.hamcrest.Matchers.startsWith("Bearer")))
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.status").value(401))
        .andExpect(jsonPath("$.title").value("Unauthorized"))
        .andExpect(jsonPath("$.correlationId").value("corr-401"));
  }

  @Test
  void hostAllowlistStillRunsBeforeAuthentication() throws Exception {
    mvc.perform(get("/api/v1/patients").header("Host", "evil.example.com"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void ackWithoutTokenIs401AndNothingIsAcknowledged() throws Exception {
    mvc.perform(post(ACK).contentType(MediaType.APPLICATION_JSON).content("{\"by\":\"mallory\"}"))
        .andExpect(status().isUnauthorized());

    verify(acks, never()).acknowledge(any(), any(), any());
  }

  @Test
  void streamWithoutTokenIs401() throws Exception {
    mvc.perform(get("/api/v1/alerts/stream").accept(MediaType.TEXT_EVENT_STREAM))
        .andExpect(status().isUnauthorized());

    verify(hub, never()).register();
  }

  @Test
  void garbageTokenIs401WithInvalidTokenChallenge() throws Exception {
    mvc.perform(get("/api/v1/patients").header("Authorization", bearer("not-a-jwt")))
        .andExpect(status().isUnauthorized())
        .andExpect(
            header().string("WWW-Authenticate", org.hamcrest.Matchers.containsString("Bearer")))
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
  }

  @Test
  void expiredTokenIs401() throws Exception {
    Instant now = Instant.now();
    var claims = TestTokens.validClaims(now);
    claims.put("iat", now.minusSeconds(3600));
    claims.put("exp", now.minusSeconds(120));
    String expired = TestTokens.hs256(TestTokens.SECRET, claims);

    mvc.perform(get("/api/v1/patients").header("Authorization", bearer(expired)))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void tokenWithWrongAudienceIs401() throws Exception {
    var claims = TestTokens.validClaims(Instant.now());
    claims.put("aud", java.util.List.of("copilot"));

    mvc.perform(
            get("/api/v1/patients")
                .header("Authorization", bearer(TestTokens.hs256(TestTokens.SECRET, claims))))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void tokenInQueryStringIsNotAccepted() throws Exception {
    String token = TestTokens.valid("nurse.levi", "Nurse Levi", "CLINICIAN");

    mvc.perform(get("/api/v1/patients").param("access_token", token))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void aRealSignedTokenReachesTheController() throws Exception {
    when(patients.list()).thenReturn(java.util.List.of());

    mvc.perform(
            get("/api/v1/patients")
                .header(
                    "Authorization",
                    bearer(TestTokens.valid("observer", "Observer", "VIEWER"))))
        .andExpect(status().isOk());
  }

  // ---- 403 -----------------------------------------------------------------------------------

  @Test
  void viewerAckIs403ProblemJson() throws Exception {
    mvc.perform(
            post(ACK)
                .with(as("observer", "Observer", "VIEWER"))
                .header("X-Correlation-Id", "corr-403")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"note\":\"x\"}"))
        .andExpect(status().isForbidden())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.status").value(403))
        .andExpect(jsonPath("$.title").value("Forbidden"))
        .andExpect(jsonPath("$.correlationId").value("corr-403"));

    verify(acks, never()).acknowledge(any(), any(), any());
  }

  @Test
  void serviceAckIs403() throws Exception {
    mvc.perform(post(ACK).with(as("copilot", null, "SERVICE")).contentType("application/json"))
        .andExpect(status().isForbidden());
  }

  @Test
  void tokenWithOnlyUnknownRolesIs403EvenForReads() throws Exception {
    mvc.perform(get("/api/v1/patients").header("Authorization", bearer(adminToken())))
        .andExpect(status().isForbidden())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
  }

  private static String adminToken() {
    return TestTokens.valid("root", "Root", "ADMIN");
  }

  @Test
  void anythingElseUnderApiIsDeniedEvenForClinicians() throws Exception {
    var clinician = as("dr.cohen", "Dr. Cohen", "CLINICIAN");

    mvc.perform(post("/api/v1/patients").with(clinician).content("{}"))
        .andExpect(status().isForbidden());
    mvc.perform(delete("/api/v1/alerts/" + UUID.randomUUID()).with(clinician))
        .andExpect(status().isForbidden());
    mvc.perform(put("/api/v1/alerts/" + UUID.randomUUID()).with(clinician).content("{}"))
        .andExpect(status().isForbidden());
    mvc.perform(post("/api/v1/alerts/stream").with(clinician)).andExpect(status().isForbidden());
  }

  // ---- allowed -------------------------------------------------------------------------------

  @Test
  void everyRoleMayRead() throws Exception {
    when(patients.list()).thenReturn(java.util.List.of());
    for (String role : new String[] {"CLINICIAN", "VIEWER", "SERVICE"}) {
      mvc.perform(get("/api/v1/patients").with(as("u", null, role))).andExpect(status().isOk());
    }
  }

  @Test
  void everyRoleMayOpenTheAlertStream() throws Exception {
    when(hub.register()).thenReturn(new SseEmitter());
    for (String role : new String[] {"CLINICIAN", "VIEWER", "SERVICE"}) {
      mvc.perform(
              get("/api/v1/alerts/stream")
                  .accept(MediaType.TEXT_EVENT_STREAM)
                  .with(as("u", null, role)))
          .andExpect(request().asyncStarted());
    }
  }

  @Test
  void healthAndPrometheusAreOpenWithoutToken() throws Exception {
    // no actuator in the slice: 404 proves the security chain let the request through
    mvc.perform(get("/actuator/health")).andExpect(status().isNotFound());
    mvc.perform(get("/actuator/health/liveness")).andExpect(status().isNotFound());
    mvc.perform(get("/actuator/prometheus")).andExpect(status().isNotFound());
  }

  @Test
  void otherActuatorEndpointsNeedAToken() throws Exception {
    mvc.perform(get("/actuator/env")).andExpect(status().isUnauthorized());
  }

  @Test
  void corsPreflightNeedsNoToken() throws Exception {
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options(ACK)
                .header("Origin", "http://localhost:3000")
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "authorization,content-type"))
        .andExpect(status().isOk());
  }

  // ---- ack actor -----------------------------------------------------------------------------

  @Test
  void clinicianAckUsesTheTokenNameAndIgnoresByInTheBody() throws Exception {
    UUID id = UUID.randomUUID();
    AlertView acked =
        AlertView.from(
            TestAlerts.alert(Severity.HIGH).acknowledge("Nurse Levi", "on it", Instant.now()));
    when(acks.acknowledge(id, new AckActor("nurse.levi", "Nurse Levi"), "on it"))
        .thenReturn(acked);

    mvc.perform(
            post("/api/v1/alerts/" + id + "/ack")
                .with(as("nurse.levi", "Nurse Levi", "CLINICIAN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"by\":\"mallory\",\"note\":\"on it\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.acknowledgedBy").value("Nurse Levi"));

    verify(acks).acknowledge(id, new AckActor("nurse.levi", "Nurse Levi"), "on it");
  }

  @Test
  void ackFallsBackToSubWhenTheTokenHasNoName() throws Exception {
    UUID id = UUID.randomUUID();
    when(acks.acknowledge(id, new AckActor("dr.cohen", "dr.cohen"), null))
        .thenReturn(AlertView.from(TestAlerts.alert(Severity.HIGH)));

    mvc.perform(
            post("/api/v1/alerts/" + id + "/ack")
                .with(as("dr.cohen", null, "CLINICIAN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isOk());
  }

  @Test
  void ackAcceptsAMissingBody() throws Exception {
    UUID id = UUID.randomUUID();
    when(acks.acknowledge(id, new AckActor("dr.cohen", "Dr. Cohen"), null))
        .thenReturn(AlertView.from(TestAlerts.alert(Severity.HIGH)));

    mvc.perform(post("/api/v1/alerts/" + id + "/ack").with(as("dr.cohen", "Dr. Cohen", "CLINICIAN")))
        .andExpect(status().isOk());
  }

  @Test
  void ackRejectsAnOverlongNote() throws Exception {
    mvc.perform(
            post(ACK)
                .with(as("dr.cohen", "Dr. Cohen", "CLINICIAN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"note\":\"" + "x".repeat(1001) + "\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void jwtRolesClaimDrivesTheAuthoritiesOfARealToken() throws Exception {
    UUID id = UUID.randomUUID();
    when(acks.acknowledge(id, new AckActor("nurse.levi", "Nurse Levi"), null))
        .thenReturn(AlertView.from(TestAlerts.alert(Severity.HIGH)));
    String clinician = TestTokens.valid("nurse.levi", "Nurse Levi", "CLINICIAN");
    String viewer = TestTokens.valid("observer", "Observer", "VIEWER");

    mvc.perform(
            post("/api/v1/alerts/" + id + "/ack").header("Authorization", bearer(clinician)))
        .andExpect(status().isOk());
    mvc.perform(post("/api/v1/alerts/" + id + "/ack").header("Authorization", bearer(viewer)))
        .andExpect(status().isForbidden());
  }
}
