package io.vitalstream.clinical.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.vitalstream.clinical.infrastructure.security.TestTokens;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** CONTRACTS 4.2 against the real, fully wired application. */
class AuthApiIT extends IntegrationTestBase {

  private final JsonMapper json = JsonMapper.builder().build();

  private UUID storedAlert(String patientId) {
    UUID id = UUID.randomUUID();
    sendAlert(
        alertJson(id, patientId, "THRESHOLD", "HIGH", "HEART_RATE", "2026-11-11T20:15:00Z", "tachy"),
        patientId,
        "c-auth");
    awaitAlertStored(id);
    return id;
  }

  @Test
  void everyApiCallWithoutATokenIs401ProblemJsonWithBearerChallenge() throws Exception {
    for (String path :
        List.of("/api/v1/patients", "/api/v1/alerts", "/api/v1/alerts/" + UUID.randomUUID())) {
      HttpResponse<String> response = get(path, null);

      assertThat(response.statusCode()).as(path).isEqualTo(401);
      assertThat(response.headers().firstValue("WWW-Authenticate").orElse("")).startsWith("Bearer");
      assertThat(response.headers().firstValue("Content-Type").orElse(""))
          .contains("application/problem+json");
      assertThat(response.headers().firstValue("X-Correlation-Id")).isPresent();
    }
    assertThat(postJson("/api/v1/alerts/" + UUID.randomUUID() + "/ack", "{}", null).statusCode())
        .isEqualTo(401);
  }

  @Test
  void healthAndPrometheusStayOpen() throws Exception {
    assertThat(get("/actuator/health", null).statusCode()).isEqualTo(200);
    assertThat(get("/actuator/health/liveness", null).statusCode()).isEqualTo(200);
    assertThat(get("/actuator/prometheus", null).statusCode()).isEqualTo(200);
  }

  @Test
  void viewerCanReadButNotAcknowledge_clinicianAcknowledgesAsTheTokenName() throws Exception {
    UUID id = storedAlert("P-2101");
    String viewer = TestTokens.valid("observer", "Observer", "VIEWER");
    String clinician = TestTokens.valid("nurse.levi", "Nurse Levi", "CLINICIAN");

    HttpResponse<String> read = get("/api/v1/alerts/" + id, viewer);
    HttpResponse<String> denied =
        postJson("/api/v1/alerts/" + id + "/ack", "{\"note\":\"x\"}", viewer);
    HttpResponse<String> ok =
        postJson("/api/v1/alerts/" + id + "/ack", "{\"by\":\"mallory\",\"note\":\"x\"}", clinician);

    assertThat(read.statusCode()).isEqualTo(200);
    assertThat(denied.statusCode()).isEqualTo(403);
    assertThat(denied.headers().firstValue("Content-Type").orElse("")).contains("problem+json");
    assertThat(ok.statusCode()).isEqualTo(200);
    JsonNode body = json.readTree(ok.body());
    assertThat(body.path("acknowledgedBy").asString()).isEqualTo("Nurse Levi");
    assertThat(body.path("acknowledgementNote").asString()).isEqualTo("x");
  }

  @Test
  void ackFallsBackToSubAndSecondAckIs409() throws Exception {
    UUID id = storedAlert("P-2102");
    String noName = TestTokens.valid("dr.cohen", null, "CLINICIAN");

    HttpResponse<String> first = postJson("/api/v1/alerts/" + id + "/ack", "{}", noName);
    HttpResponse<String> second = postJson("/api/v1/alerts/" + id + "/ack", "{}", noName);

    assertThat(json.readTree(first.body()).path("acknowledgedBy").asString()).isEqualTo("dr.cohen");
    assertThat(second.statusCode()).isEqualTo(409);
  }

  @Test
  void expiredAndForgedTokensAreRejected() throws Exception {
    Instant now = Instant.now();
    var expired = TestTokens.validClaims(now);
    expired.put("iat", now.minusSeconds(7200));
    expired.put("exp", now.minusSeconds(3600));
    String forged =
        TestTokens.hs256(
            "forged-forged-forged-forged-forged-forged", TestTokens.validClaims(now));

    assertThat(get("/api/v1/patients", TestTokens.hs256(TestTokens.SECRET, expired)).statusCode())
        .isEqualTo(401);
    assertThat(get("/api/v1/patients", forged).statusCode()).isEqualTo(401);
  }

  @Test
  void streamNeedsABearerHeader() throws Exception {
    assertThat(get("/api/v1/alerts/stream", null).statusCode()).isEqualTo(401);

    HttpResponse<java.io.InputStream> open =
        http.send(
            HttpRequest.newBuilder(URI.create(url("/api/v1/alerts/stream")))
                .header("Authorization", "Bearer " + TestTokens.valid("o", null, "VIEWER"))
                .header("Accept", "text/event-stream")
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofInputStream());
    try (java.io.InputStream body = open.body()) {
      assertThat(open.statusCode()).isEqualTo(200);
    }
  }

  @Test
  void devTokenEndpointIssuesTokensTheApiAcceptsInDev() throws Exception {
    HttpResponse<String> issued =
        postJson(
            "/api/v1/dev/token",
            "{\"sub\":\"copilot\",\"roles\":[\"SERVICE\"]}",
            null);
    String token = json.readTree(issued.body()).path("token").asString();

    assertThat(issued.statusCode()).isEqualTo(200);
    assertThat(get("/api/v1/patients", token).statusCode()).isEqualTo(200);
    assertThat(
            postJson("/api/v1/alerts/" + UUID.randomUUID() + "/ack", "{}", token).statusCode())
        .isEqualTo(403);
  }
}
