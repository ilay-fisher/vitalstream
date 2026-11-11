package io.vitalstream.ingest.api;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.vitalstream.ingest.Fixtures;
import io.vitalstream.ingest.domain.service.IngestResult;
import io.vitalstream.ingest.domain.service.IngestService;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * URL-variant checks at the filter level. The raw request URI is injected exactly as given (no
 * re-encoding), the way a servlet container hands it to filters.
 */
@WebMvcTest(VitalsController.class)
@Import(WebTestConfig.class)
class ApiKeyBypassMvcTest {

  @Autowired private MockMvc mvc;
  @MockitoBean private IngestService ingest;

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/api/v1;x=1/vitals/batch",
        "/api/v1/vitals/batch;jsessionid=abc",
        "/%61pi/v1/vitals/batch",
        "/api/v1/vit%61ls/batch",
        "/api/v1//vitals/batch",
        "/api/v1/./vitals/batch",
        "/api/v1/../v1/vitals/batch",
        "/api/v1/%2e/vitals/batch",
        "/api/v1/vitals%2Fbatch",
        "/api%5Cv1/vitals/batch",
        "/%2561pi/v1/vitals/batch",
        "/API/v1/vitals/batch",
        "/api/V1/vitals/batch",
        "/api/v1/vitals/batch/",
        "/actuator/health/../../api/v1/vitals/batch",
        "/swagger-ui/../api/v1/vitals/batch",
        "/v3/api-docs/../api/v1/vitals/batch"
      })
  void variantWithoutKeyIsNeverServed(String rawPath) throws Exception {
    when(ingest.ingest(anyList(), anyList())).thenReturn(new IngestResult(1, 0, List.of()));

    int status =
        mvc.perform(
                post(URI.create(rawPath))
                    .header("Host", "localhost")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(Fixtures.read("batch.json")))
            .andReturn()
            .getResponse()
            .getStatus();

    org.assertj.core.api.Assertions.assertThat(status).as(rawPath).isIn(400, 401);
    verify(ingest, never()).ingest(anyList(), anyList());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/api/v1;x=1/vitals/batch",
        "/api/v1/./vitals/batch",
        "/api/v1/../v1/vitals/batch",
        "/api/v1/vitals%2Fbatch",
        "/api/v1/vitals%5Cbatch",
        "/api/v1/vitals%25batch",
        "/api/v1/vitals%2ebatch"
      })
  void ambiguousUriIsRejectedWith400EvenWithValidKey(String rawPath) throws Exception {
    mvc.perform(
            post(URI.create(rawPath))
                .header("Host", "localhost")
                .header("X-Api-Key", "dev-ingest-key")
                .contentType(MediaType.APPLICATION_JSON)
                .content(Fixtures.read("batch.json")))
        .andExpect(status().isBadRequest());

    verify(ingest, never()).ingest(anyList(), anyList());
  }
}
