package io.vitalstream.clinical.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.datastax.oss.driver.api.core.CqlSession;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class PatientsAndVitalsIT extends IntegrationTestBase {

  private final JsonMapper json = JsonMapper.builder().build();

  @Autowired StringRedisTemplate redis;
  @Autowired MeterRegistry meters;

  @Test
  void patientListShowsLatestVitalsFromRedisAndOpenAlertCounts() throws Exception {
    redis
        .opsForHash()
        .put(
            "patient:P-0002:latest",
            "HEART_RATE",
            "{\"value\":97.0,\"unit\":\"/min\",\"observedAt\":\"2026-11-11T20:15:30.123Z\"}");
    sendAlert(
        alertJson(
            UUID.randomUUID(),
            "P-0002",
            "NEWS2",
            "HIGH",
            null,
            "2026-11-11T20:15:00Z",
            "news2 seven"),
        "P-0002",
        "c-p");
    org.awaitility.Awaitility.await()
        .atMost(java.time.Duration.ofSeconds(30))
        .until(() -> alertRepository.openCount("P-0002") >= 1L);

    JsonNode list = json.readTree(get("/api/v1/patients").body());
    JsonNode detail = json.readTree(get("/api/v1/patients/P-0002").body());

    assertThat(list).hasSizeGreaterThanOrEqualTo(3);
    assertThat(detail.path("name").asString()).isEqualTo("Omer Katz");
    assertThat(detail.path("latestVitals").path("HEART_RATE").path("value").asDouble())
        .isEqualTo(97.0);
    assertThat(detail.path("openAlerts").asLong()).isGreaterThanOrEqualTo(1L);
    assertThat(get("/api/v1/patients/P-9999").statusCode()).isEqualTo(404);
  }

  @Test
  void historyComesFromCassandraAndIsCachedForTenSeconds() throws Exception {
    LocalDate day = LocalDate.of(2026, 11, 11);
    try (CqlSession session = Infra.cassandraSession("vitalstream")) {
      session.execute(
          "INSERT INTO vitals_raw (patient_id, day, observed_at, metric, event_id, value, unit, device_id)"
              + " VALUES ('P-0003', ?, '2026-11-11T20:15:30Z', 'HEART_RATE', ?, 101, '/min', 'DEV-ICU-0003')",
          day,
          UUID.randomUUID());
      session.execute(
          "INSERT INTO vitals_raw (patient_id, day, observed_at, metric, event_id, value, unit, device_id)"
              + " VALUES ('P-0003', ?, '2026-11-11T20:16:30Z', 'SPO2', ?, 95, '%', 'DEV-ICU-0003')",
          day, UUID.randomUUID());
      session.execute(
          "INSERT INTO vitals_by_minute (patient_id, metric, day, window_start, min, max, avg, count)"
              + " VALUES ('P-0003', 'HEART_RATE', ?, '2026-11-11T20:15:00Z', 98, 104, 101, 12)",
          day);
    }
    String raw = "/api/v1/patients/P-0003/vitals?from=2026-11-11T20:00:00Z&to=2026-11-11T21:00:00Z";
    double hitsBefore = counter("hit");

    JsonNode first = json.readTree(get(raw).body());
    JsonNode second = json.readTree(get(raw).body());
    JsonNode heartRate = json.readTree(get(raw + "&metric=HEART_RATE").body());
    JsonNode minute = json.readTree(get(raw + "&resolution=minute&metric=HEART_RATE").body());

    assertThat(first.path("points")).hasSize(2);
    assertThat(first.path("points").get(0).path("metric").asString()).isEqualTo("HEART_RATE");
    assertThat(second).isEqualTo(first);
    assertThat(counter("hit")).isEqualTo(hitsBefore + 1);
    assertThat(redis.keys("cache:vitals:P-0003:*")).isNotEmpty();
    assertThat(redis.getExpire(redis.keys("cache:vitals:P-0003:*").iterator().next()))
        .isBetween(1L, 10L);
    assertThat(heartRate.path("points")).hasSize(1);
    assertThat(minute.path("points").get(0).path("avg").asDouble()).isEqualTo(101.0);
    assertThat(minute.path("points").get(0).path("count").asInt()).isEqualTo(12);
    assertThat(day.atStartOfDay().toInstant(ZoneOffset.UTC)).isNotNull();
  }

  @Test
  void historyForUnknownPatientIs404AndRangeIsValidated() throws Exception {
    assertThat(get("/api/v1/patients/P-9999/vitals").statusCode()).isEqualTo(404);
    assertThat(get("/api/v1/patients/P-0001/vitals?resolution=hourly").statusCode()).isEqualTo(400);
  }

  private double counter(String result) {
    var c = meters.find("cache.requests").tag("cache", "vitals").tag("result", result).counter();
    return c == null ? 0 : c.count();
  }
}
