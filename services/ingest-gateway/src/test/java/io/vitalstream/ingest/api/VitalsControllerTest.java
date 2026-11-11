package io.vitalstream.ingest.api;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.vitalstream.ingest.Fixtures;
import io.vitalstream.ingest.domain.model.Rejection;
import io.vitalstream.ingest.domain.model.RejectionReason;
import io.vitalstream.ingest.domain.service.IngestResult;
import io.vitalstream.ingest.domain.service.IngestService;
import io.vitalstream.ingest.domain.service.PublishFailedException;
import io.vitalstream.ingest.domain.service.RateLimitExceededException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(VitalsController.class)
@Import(WebTestConfig.class)
class VitalsControllerTest {

  @Autowired private MockMvc mvc;
  @MockitoBean private IngestService ingest;

  private org.springframework.test.web.servlet.ResultActions send(String body) throws Exception {
    return mvc.perform(
        post("/api/v1/vitals/batch")
            .header("Host", "localhost")
            .header("X-Api-Key", "dev-ingest-key")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body));
  }

  @Test
  void returns202WithContractShapedBody() throws Exception {
    when(ingest.ingest(anyList(), anyList()))
        .thenReturn(
            new IngestResult(
                2,
                0,
                List.of(
                    new Rejection(
                        "8f1c2a1e-8d0b-4c11-9a77-3f0c2b9e1d10",
                        RejectionReason.OUT_OF_RANGE,
                        "HEART_RATE 300.0 outside range"))));

    send(Fixtures.read("batch.json"))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.accepted").value(2))
        .andExpect(jsonPath("$.duplicates").value(0))
        .andExpect(jsonPath("$.rejected[0].eventId").value("8f1c2a1e-8d0b-4c11-9a77-3f0c2b9e1d10"))
        .andExpect(
            jsonPath("$.rejected[0].reason").value("OUT_OF_RANGE: HEART_RATE 300.0 outside range"));
  }

  @Test
  void echoesSuppliedCorrelationId() throws Exception {
    when(ingest.ingest(anyList(), anyList())).thenReturn(new IngestResult(1, 0, List.of()));

    mvc.perform(
            post("/api/v1/vitals/batch")
                .header("Host", "localhost")
                .header("X-Api-Key", "dev-ingest-key")
                .header("X-Correlation-Id", "abc-123")
                .contentType(MediaType.APPLICATION_JSON)
                .content(Fixtures.read("batch.json")))
        .andExpect(header().string("X-Correlation-Id", "abc-123"));
  }

  @Test
  void generatesCorrelationIdWhenAbsent() throws Exception {
    when(ingest.ingest(anyList(), anyList())).thenReturn(new IngestResult(1, 0, List.of()));

    send(Fixtures.read("batch.json")).andExpect(header().exists("X-Correlation-Id"));
  }

  @Test
  void missingReadingsIsA400Problem() throws Exception {
    send("{\"deviceId\":\"DEV-ICU-0001\",\"patientId\":\"P-0001\"}")
        .andExpect(status().isBadRequest())
        .andExpect(header().string("Content-Type", "application/problem+json"))
        .andExpect(jsonPath("$.status").value(400))
        .andExpect(jsonPath("$.correlationId").exists());
  }

  @Test
  void emptyReadingsIsA400() throws Exception {
    send("{\"deviceId\":\"DEV-ICU-0001\",\"patientId\":\"P-0001\",\"readings\":[]}")
        .andExpect(status().isBadRequest());
  }

  @Test
  void moreThan500ReadingsIsA400() throws Exception {
    String reading =
        "{\"eventId\":\"6f1c2a1e-8d0b-4c11-9a77-3f0c2b9e1d10\",\"metric\":\"SPO2\",\"value\":97,\"observedAt\":\"2026-11-11T20:15:30Z\"}";
    String body =
        "{\"deviceId\":\"DEV-ICU-0001\",\"patientId\":\"P-0001\",\"readings\":["
            + String.join(",", java.util.Collections.nCopies(501, reading))
            + "]}";

    send(body).andExpect(status().isBadRequest());
  }

  @Test
  void invalidJsonIsA400Problem() throws Exception {
    send("{not json")
        .andExpect(status().isBadRequest())
        .andExpect(header().string("Content-Type", "application/problem+json"));
  }

  @Test
  void wrongContentTypeIs415() throws Exception {
    mvc.perform(
            post("/api/v1/vitals/batch")
                .header("Host", "localhost")
                .header("X-Api-Key", "dev-ingest-key")
                .contentType(MediaType.TEXT_PLAIN)
                .content("x"))
        .andExpect(status().isUnsupportedMediaType());
  }

  @Test
  void rateLimitMapsTo429WithRetryAfter() throws Exception {
    when(ingest.ingest(anyList(), anyList()))
        .thenThrow(new RateLimitExceededException("DEV-ICU-0001", 3));

    send(Fixtures.read("batch.json"))
        .andExpect(status().isTooManyRequests())
        .andExpect(header().string("Retry-After", "3"))
        .andExpect(header().string("Content-Type", "application/problem+json"))
        .andExpect(jsonPath("$.type").value("urn:vitalstream:problem:rate-limited"))
        .andExpect(jsonPath("$.correlationId").exists());
  }

  @Test
  void publishFailureMapsTo503() throws Exception {
    when(ingest.ingest(anyList(), anyList()))
        .thenThrow(new PublishFailedException("1 of 1 not acknowledged", new RuntimeException()));

    send(Fixtures.read("batch.json"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(header().string("Retry-After", "1"))
        .andExpect(jsonPath("$.title").value("Event log unavailable"));
  }

  @Test
  void redisFailureMapsTo503() throws Exception {
    when(ingest.ingest(anyList(), anyList())).thenThrow(new QueryTimeoutException("redis timeout"));

    send(Fixtures.read("batch.json"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.title").value("Dependency unavailable"));
  }

  @Test
  void unexpectedErrorMapsTo500WithoutLeakingDetails() throws Exception {
    when(ingest.ingest(anyList(), anyList()))
        .thenThrow(new IllegalStateException("secret internals"));

    send(Fixtures.read("batch.json"))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.detail").value("An unexpected error occurred"));
  }

  @Test
  void missingApiKeyIs401ProblemAndNothingIsIngested() throws Exception {
    mvc.perform(
            post("/api/v1/vitals/batch")
                .header("Host", "localhost")
                .contentType(MediaType.APPLICATION_JSON)
                .content(Fixtures.read("batch.json")))
        .andExpect(status().isUnauthorized())
        .andExpect(header().string("Content-Type", "application/problem+json"))
        .andExpect(jsonPath("$.correlationId").exists());

    org.mockito.Mockito.verifyNoInteractions(ingest);
  }

  @Test
  void wrongApiKeyIs401() throws Exception {
    mvc.perform(
            post("/api/v1/vitals/batch")
                .header("Host", "localhost")
                .header("X-Api-Key", "nope")
                .contentType(MediaType.APPLICATION_JSON)
                .content(Fixtures.read("batch.json")))
        .andExpect(status().isUnauthorized());
  }
}
