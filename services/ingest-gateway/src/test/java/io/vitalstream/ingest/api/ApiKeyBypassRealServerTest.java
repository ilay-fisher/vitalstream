package io.vitalstream.ingest.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.vitalstream.ingest.Fixtures;
import io.vitalstream.ingest.domain.service.IngestResult;
import io.vitalstream.ingest.domain.service.IngestService;
import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Proves the API-key check cannot be bypassed by URL tricks against the real embedded Tomcat. The
 * raw request line is written to a socket, byte for byte, because HTTP clients normalise or
 * re-encode the path and would hide exactly the variants an attacker sends. {@code IngestService}
 * is mocked so a bypass shows up as a 2xx and a recorded call instead of a downstream failure.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiKeyBypassRealServerTest {

  /** Every path that Spring MVC could still route to a controller, or that tries to. */
  private static final String[] BYPASS_PATHS = {
    "/api/v1;x=1/vitals/batch",
    "/api/v1/vitals/batch;jsessionid=abc",
    "/api/v1/vitals;x=1/batch",
    "/%61pi/v1/vitals/batch",
    "/api/v1/vit%61ls/batch",
    "//api/v1/vitals/batch",
    "/api//v1/vitals/batch",
    "/api/v1//vitals/batch",
    "/api/v1/./vitals/batch",
    "/./api/v1/vitals/batch",
    "/api/v1/../v1/vitals/batch",
    "/api/v1/%2e/vitals/batch",
    "/api/v1/%2E%2E/v1/vitals/batch",
    "/api/v1/vitals%2Fbatch",
    "/api%2Fv1/vitals/batch",
    "/api%5Cv1/vitals/batch",
    "/api/v1\\vitals/batch",
    "/%2561pi/v1/vitals/batch",
    "/API/v1/vitals/batch",
    "/api/V1/vitals/batch",
    "/api/v1/vitals/BATCH",
    "/api/v1/vitals/batch/",
    "/api/v1/vitals/batch%00",
    "/actuator/health/../../api/v1/vitals/batch",
    "/actuator/health;/../api/v1/vitals/batch",
    "/v3/api-docs/../api/v1/vitals/batch",
    "/swagger-ui/../api/v1/vitals/batch"
  };

  @Value("${local.server.port}")
  private int port;

  @MockitoBean private IngestService ingest;

  @BeforeEach
  void stubIngest() {
    when(ingest.ingest(anyList(), anyList())).thenReturn(new IngestResult(1, 0, List.of()));
  }

  /** Sends one POST with the exact request-target and returns the HTTP status code. */
  private int status(String method, String rawPath, String apiKey) throws IOException {
    String body = "GET".equals(method) ? "" : Fixtures.read("batch.json");
    StringBuilder request =
        new StringBuilder(method + " " + rawPath + " HTTP/1.1\r\n")
            .append("Host: localhost\r\n")
            .append("Connection: close\r\n")
            .append(body.isEmpty() ? "" : "Content-Type: application/json\r\n")
            .append("Content-Length: " + body.getBytes(StandardCharsets.UTF_8).length + "\r\n");
    if (apiKey != null) {
      request.append("X-Api-Key: " + apiKey + "\r\n");
    }
    request.append("\r\n").append(body);
    try (Socket socket = new Socket("localhost", port)) {
      socket.setSoTimeout(15_000);
      socket.getOutputStream().write(request.toString().getBytes(StandardCharsets.UTF_8));
      String response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      return Integer.parseInt(response.substring(9, 12));
    }
  }

  @Test
  void canonicalPathWithKeyReachesTheController() throws Exception {
    assertThat(status("POST", "/api/v1/vitals/batch", "dev-ingest-key")).isEqualTo(202);
  }

  @Test
  void canonicalPathWithoutKeyIs401() throws Exception {
    assertThat(status("POST", "/api/v1/vitals/batch", null)).isEqualTo(401);
    verify(ingest, never()).ingest(anyList(), anyList());
  }

  /** Collects every offending variant so one run lists all of them, not just the first. */
  private List<String> served(String apiKey) throws IOException {
    List<String> offenders = new java.util.ArrayList<>();
    for (String path : BYPASS_PATHS) {
      int status = status("POST", path, apiKey);
      if (status != 400 && status != 401) {
        offenders.add(path + " -> " + status);
      }
    }
    return offenders;
  }

  @Test
  void noBypassVariantReachesAControllerWithoutTheKey() throws Exception {
    assertThat(served(null)).as("variants served without a key").isEmpty();
    verify(ingest, never()).ingest(anyList(), anyList());
  }

  @Test
  void noBypassVariantWorksWithAWrongKey() throws Exception {
    assertThat(served("wrong")).as("variants served with a wrong key").isEmpty();
    verify(ingest, never()).ingest(anyList(), anyList());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/api/v1;x=1/vitals/batch",
        "/api/v1/./vitals/batch",
        "//api/v1/vitals/batch",
        "/api/v1/vitals%2Fbatch"
      })
  void ambiguousPathsAreRefusedEvenWithAValidKey(String path) throws Exception {
    assertThat(status("POST", path, "dev-ingest-key")).isEqualTo(400);
    verify(ingest, never()).ingest(anyList(), anyList());
  }

  @Test
  void otherMethodsAndUnknownPathsAlsoNeedTheKey() throws Exception {
    assertThat(status("GET", "/api/v1/vitals/batch", null)).isEqualTo(401);
    assertThat(status("PUT", "/api/v1/hl7", null)).isEqualTo(401);
    assertThat(status("POST", "/anything/else", null)).isEqualTo(401);
    assertThat(status("POST", "/", null)).isEqualTo(401);
    assertThat(status("POST", "/actuator/env", null)).isEqualTo(401);
  }

  @Test
  void openPathsStillWorkWithoutTheKey() throws Exception {
    assertThat(status("GET", "/v3/api-docs", null)).isEqualTo(200);
    assertThat(status("GET", "/swagger-ui/index.html", null)).isEqualTo(200);
    assertThat(status("GET", "/actuator/prometheus", null)).isEqualTo(200);
    assertThat(status("GET", "/actuator/health", null)).isNotIn(400, 401);
    assertThat(status("GET", "/actuator/health/liveness", null)).isNotIn(400, 401);
  }
}
