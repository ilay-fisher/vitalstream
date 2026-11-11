package io.vitalstream.clinical.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.vitalstream.clinical.domain.Alert;
import io.vitalstream.clinical.domain.AlertType;
import io.vitalstream.clinical.domain.Severity;
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

  @Test
  void ackTransitionsOnceBumpsVersionAndUpdatesTheSearchIndex() throws Exception {
    UUID id = UUID.randomUUID();
    sendAlert(
        alertJson(
            id,
            "P-2001",
            "THRESHOLD",
            "HIGH",
            "RESP_RATE",
            "2026-11-11T20:15:00Z",
            "respiratory distress"),
        "P-2001",
        "c-ack");
    awaitAlertStored(id);

    HttpResponse<String> ok =
        postJson("/api/v1/alerts/" + id + "/ack", "{\"by\":\"dr.cohen\",\"note\":\"bedside now\"}");
    HttpResponse<String> again =
        postJson("/api/v1/alerts/" + id + "/ack", "{\"by\":\"nurse.levi\"}");

    assertThat(ok.statusCode()).isEqualTo(200);
    JsonNode body = json.readTree(ok.body());
    assertThat(body.path("status").asString()).isEqualTo("ACKNOWLEDGED");
    assertThat(body.path("acknowledgedBy").asString()).isEqualTo("dr.cohen");
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
  void staleVersionLosesTheOptimisticLock() throws Exception {
    UUID id = UUID.randomUUID();
    sendAlert(
        alertJson(id, "P-2002", "NEWS2", "LOW", null, "2026-11-11T20:15:00Z", "score"),
        "P-2002",
        "c-lock");
    awaitAlertStored(id);
    Alert stored = alertRepository.findById(id).orElseThrow();
    Alert acked = stored.acknowledge("a", null, Instant.now());

    assertThat(alertRepository.updateAcknowledgement(acked, stored.version() + 5)).isFalse();
    assertThat(alertRepository.updateAcknowledgement(acked, stored.version())).isTrue();
    assertThat(alertRepository.updateAcknowledgement(acked, stored.version())).isFalse();
  }

  @Test
  void listFiltersPaginatesAndSearchesText() throws Exception {
    for (int i = 0; i < 3; i++) {
      UUID id = UUID.randomUUID();
      sendAlert(
          alertJson(
              id,
              "P-2003",
              "THRESHOLD",
              i == 0 ? "CRITICAL" : "MEDIUM",
              "TEMPERATURE",
              "2026-11-11T20:1" + i + ":00Z",
              "fever pattern " + i),
          "P-2003",
          "c-list");
      awaitAlertStored(id);
    }

    JsonNode all = json.readTree(get("/api/v1/alerts?patientId=P-2003&size=2&page=0").body());
    JsonNode second = json.readTree(get("/api/v1/alerts?patientId=P-2003&size=2&page=1").body());
    JsonNode critical =
        json.readTree(get("/api/v1/alerts?patientId=P-2003&severity=CRITICAL").body());

    assertThat(all.path("totalElements").asLong()).isEqualTo(3L);
    assertThat(all.path("totalPages").asInt()).isEqualTo(2);
    assertThat(all.path("items")).hasSize(2);
    assertThat(second.path("items")).hasSize(1);
    assertThat(critical.path("totalElements").asLong()).isEqualTo(1L);
    await()
        .atMost(Duration.ofSeconds(20))
        .until(() ->
            json.readTree(get("/api/v1/alerts?q=fever&patientId=P-2003&severity=MEDIUM").body())
                    .path("totalElements")
                    .asLong()
                == 2L);
  }

  @Test
  void unknownAlertAndBadParametersAreProblemJson() throws Exception {
    HttpResponse<String> missing = get("/api/v1/alerts/" + UUID.randomUUID());
    HttpResponse<String> bad = get("/api/v1/alerts?size=0");

    assertThat(missing.statusCode()).isEqualTo(404);
    assertThat(missing.headers().firstValue("Content-Type").orElse("")).contains("problem+json");
    assertThat(missing.headers().firstValue("X-Correlation-Id")).isPresent();
    assertThat(bad.statusCode()).isEqualTo(400);
  }

  @Test
  void doubleInsertOfTheSameAlertIsANoOp() {
    Alert alert =
        Alert.open(
            UUID.randomUUID(),
            "P-2004",
            AlertType.NEWS2,
            Severity.HIGH,
            null,
            null,
            7.0,
            "m",
            Instant.now(),
            Instant.now(),
            Instant.now());

    assertThat(alertRepository.insertIfAbsent(alert)).isTrue();
    assertThat(alertRepository.insertIfAbsent(alert)).isFalse();
    assertThat(alertRepository.openCount("P-2004")).isEqualTo(1L);
    assertThat(alertRepository.openCountsByPatient()).containsEntry("P-2004", 1L);
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
