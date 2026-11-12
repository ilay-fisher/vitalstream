package io.vitalstream.clinical.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;

import io.vitalstream.clinical.domain.Alert;
import io.vitalstream.clinical.domain.AlertType;
import io.vitalstream.clinical.domain.Detection;
import io.vitalstream.clinical.domain.Severity;
import io.vitalstream.clinical.infrastructure.security.TestTokens;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class AlertsApiIT extends IntegrationTestBase {

  private final JsonMapper json = JsonMapper.builder().build();

  private UUID episodeFor(
      String patient,
      String type,
      String severity,
      String metric,
      String windowStart,
      String text) {
    UUID detection = UUID.randomUUID();
    sendAlert(
        alertJson(detection, patient, type, severity, metric, windowStart, text), patient, "c");
    return awaitEpisode(detection);
  }

  @Test
  void ackTransitionsOnceBumpsVersionAndUpdatesTheSearchIndex() throws Exception {
    UUID id =
        episodeFor(
            "P-2001",
            "THRESHOLD",
            "HIGH",
            "RESP_RATE",
            "2026-11-11T20:15:00Z",
            "respiratory distress");

    HttpResponse<String> ok =
        postJson(
            "/api/v1/alerts/" + id + "/ack",
            "{\"by\":\"mallory\",\"note\":\"bedside now\"}",
            TestTokens.valid("dr.cohen", "Dr. Cohen", "CLINICIAN"));
    HttpResponse<String> again = postJson("/api/v1/alerts/" + id + "/ack", "{}");

    assertThat(ok.statusCode()).isEqualTo(200);
    JsonNode body = json.readTree(ok.body());
    assertThat(body.path("status").asString()).isEqualTo("ACKNOWLEDGED");
    assertThat(body.path("acknowledgedBy").asString()).isEqualTo("Dr. Cohen");
    assertThat(body.path("version").asLong()).isEqualTo(1L);
    assertThat(again.statusCode()).isEqualTo(409);
    assertThat(again.headers().firstValue("Content-Type").orElse(""))
        .contains("application/problem+json");
    assertThat(json.readTree(get("/api/v1/alerts/" + id).body()).path("status").asString())
        .isEqualTo("ACKNOWLEDGED");
    await()
        .atMost(Duration.ofSeconds(20))
        .until(() ->
            json.readTree(
                        get("/api/v1/alerts?q=respiratory&status=ACKNOWLEDGED&patientId=P-2001")
                            .body())
                    .path("totalElements")
                    .asLong()
                == 1L);
  }

  @Test
  void ackKeepsWorkingWhileIngestKeepsUpdatingTheEpisode() throws Exception {
    UUID id =
        episodeFor("P-2005", "THRESHOLD", "HIGH", "TEMPERATURE", "2026-11-11T20:15:00Z", "fever");
    doReturn(false)
        .doCallRealMethod()
        .when(alertRepository)
        .updateAcknowledgement(org.mockito.ArgumentMatchers.any(), anyLong());

    HttpResponse<String> response = postJson("/api/v1/alerts/" + id + "/ack", "{}");

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(json.readTree(response.body()).path("status").asString()).isEqualTo("ACKNOWLEDGED");
  }

  @Test
  void ackOfAnAcknowledgedOrResolvedEpisodeIs409() throws Exception {
    UUID acked =
        episodeFor("P-2006", "THRESHOLD", "HIGH", "SPO2", "2026-11-11T20:15:00Z", "low oxygen");
    UUID resolved =
        episodeFor("P-2006", "ML_ANOMALY", "MEDIUM", null, "2026-11-11T20:15:00Z", "odd pattern");
    assertThat(postJson("/api/v1/alerts/" + acked + "/ack", "{}").statusCode()).isEqualTo(200);
    jdbc.sql("UPDATE clinical.alerts SET status = 'RESOLVED', version = version + 1 WHERE id = :id")
        .param("id", resolved)
        .update();

    assertThat(postJson("/api/v1/alerts/" + acked + "/ack", "{}").statusCode()).isEqualTo(409);
    assertThat(postJson("/api/v1/alerts/" + resolved + "/ack", "{}").statusCode()).isEqualTo(409);
  }

  @Test
  void staleVersionLosesTheOptimisticLock() throws Exception {
    UUID id = episodeFor("P-2002", "NEWS2", "LOW", null, "2026-11-11T20:15:00Z", "score");
    Alert stored = alertRepository.findById(id).orElseThrow();
    Alert acked = stored.acknowledge("a", null, Instant.now());

    assertThat(alertRepository.updateAcknowledgement(acked, stored.version() + 5)).isFalse();
    assertThat(alertRepository.updateAcknowledgement(acked, stored.version())).isTrue();
    assertThat(alertRepository.updateAcknowledgement(acked, stored.version())).isFalse();
  }

  @Test
  void listFiltersPaginatesSortsByLastSeenAndSearchesText() throws Exception {
    String patient = "P-2003";
    episodeFor(
        patient, "THRESHOLD", "CRITICAL", "TEMPERATURE", "2026-11-11T20:10:00Z", "fever pattern a");
    episodeFor(
        patient, "THRESHOLD", "MEDIUM", "HEART_RATE", "2026-11-11T20:11:00Z", "fever pattern b");
    episodeFor(
        patient, "TREND_ZSCORE", "MEDIUM", "HEART_RATE", "2026-11-11T20:12:00Z", "fever pattern c");

    JsonNode all =
        json.readTree(get("/api/v1/alerts?patientId=" + patient + "&size=2&page=0").body());
    JsonNode second =
        json.readTree(get("/api/v1/alerts?patientId=" + patient + "&size=2&page=1").body());
    JsonNode critical =
        json.readTree(get("/api/v1/alerts?patientId=" + patient + "&severity=CRITICAL").body());
    JsonNode trend =
        json.readTree(get("/api/v1/alerts?patientId=" + patient + "&type=TREND_ZSCORE").body());

    assertThat(all.path("totalElements").asLong()).isEqualTo(3L);
    assertThat(all.path("totalPages").asInt()).isEqualTo(2);
    assertThat(all.path("items")).hasSize(2);
    assertThat(all.path("items").get(0).path("metric").asString()).isEqualTo("HEART_RATE");
    assertThat(all.path("items").get(0).path("type").asString()).isEqualTo("TREND_ZSCORE");
    assertThat(all.path("items").get(1).path("lastSeenAt").asString())
        .isEqualTo("2026-11-11T20:12:00Z");
    assertThat(second.path("items")).hasSize(1);
    assertThat(critical.path("totalElements").asLong()).isEqualTo(1L);
    assertThat(trend.path("totalElements").asLong()).isEqualTo(1L);
    await()
        .atMost(Duration.ofSeconds(20))
        .until(() ->
            json.readTree(
                        get("/api/v1/alerts?q=fever&patientId=" + patient + "&severity=MEDIUM")
                            .body())
                    .path("totalElements")
                    .asLong()
                == 2L);
  }

  @Test
  void statusIsRepeatableAndTopLevelAndParentFiltersAlsoWorkOnTheSearchIndex() throws Exception {
    String patient = "P-2007";
    UUID parent =
        episodeFor(patient, "NEWS2", "HIGH", null, "2026-11-11T20:15:00Z", "septic picture");
    UUID child =
        episodeFor(
            patient,
            "THRESHOLD",
            "MEDIUM",
            "HEART_RATE",
            "2026-11-11T20:15:00Z",
            "septic tachycardia");
    assertThat(postJson("/api/v1/alerts/" + child + "/ack", "{}").statusCode()).isEqualTo(200);

    JsonNode both =
        json.readTree(
            get("/api/v1/alerts?patientId=" + patient + "&status=OPEN&status=ACKNOWLEDGED").body());
    JsonNode onlyAcked =
        json.readTree(get("/api/v1/alerts?patientId=" + patient + "&status=ACKNOWLEDGED").body());
    assertThat(both.path("totalElements").asLong()).isEqualTo(2L);
    assertThat(onlyAcked.path("totalElements").asLong()).isEqualTo(1L);

    await()
        .atMost(Duration.ofSeconds(20))
        .until(() -> {
          JsonNode top =
              json.readTree(
                  get("/api/v1/alerts?q=septic&patientId=" + patient + "&topLevel=true").body());
          JsonNode kids =
              json.readTree(
                  get("/api/v1/alerts?q=septic&patientId=" + patient + "&parentAlertId=" + parent)
                      .body());
          JsonNode multi =
              json.readTree(
                  get("/api/v1/alerts?q=septic&patientId="
                          + patient
                          + "&status=OPEN&status=ACKNOWLEDGED")
                      .body());
          return top.path("totalElements").asLong() == 1L
              && top.path("items").get(0).path("alertId").asString().equals(parent.toString())
              && kids.path("totalElements").asLong() == 1L
              && kids.path("items").get(0).path("alertId").asString().equals(child.toString())
              && multi.path("totalElements").asLong() == 2L;
        });
  }

  @Test
  void unknownAlertAndBadParametersAreProblemJson() throws Exception {
    HttpResponse<String> missing = get("/api/v1/alerts/" + UUID.randomUUID());
    HttpResponse<String> bad = get("/api/v1/alerts?size=0");
    HttpResponse<String> badParent = get("/api/v1/alerts?parentAlertId=nope");

    assertThat(missing.statusCode()).isEqualTo(404);
    assertThat(missing.headers().firstValue("Content-Type").orElse("")).contains("problem+json");
    assertThat(missing.headers().firstValue("X-Correlation-Id")).isPresent();
    assertThat(bad.statusCode()).isEqualTo(400);
    assertThat(badParent.statusCode()).isEqualTo(400);
  }

  @Test
  void aSecondNonResolvedEpisodeForTheSameKeyIsRefusedByTheUniqueIndex() {
    Detection first = detectionFor("P-2004", AlertType.NEWS2, null, 0);
    Detection other = detectionFor("P-2004", AlertType.NEWS2, null, 1);

    assertThat(alertRepository.insert(Alert.open(first))).isTrue();
    assertThat(alertRepository.insert(Alert.open(first))).isFalse();
    assertThat(alertRepository.insert(Alert.open(other))).isFalse();
    assertThat(alertRepository.openCount("P-2004")).isEqualTo(1L);
    assertThat(alertRepository.openCountsByPatient()).containsEntry("P-2004", 1L);
  }

  private static Detection detectionFor(String patient, AlertType type, String metric, int minute) {
    Instant start = Instant.parse("2026-11-11T20:15:00Z").plusSeconds(60L * minute);
    return new Detection(
        UUID.randomUUID(),
        patient,
        type,
        Severity.HIGH,
        metric,
        null,
        7.0,
        "m",
        start,
        start.plusSeconds(60),
        start.plusSeconds(62));
  }

  @Test
  void corsAllowsTheDashboardOrigins() throws Exception {
    for (String origin : new String[] {"http://localhost:3000", "http://localhost:5173"}) {
      HttpResponse<String> response =
          http.send(
              HttpRequest.newBuilder(URI.create(url("/api/v1/alerts")))
                  .header("Origin", origin)
                  .header("Access-Control-Request-Method", "GET")
                  .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).contains(origin);
    }
  }
}
