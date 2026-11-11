package io.vitalstream.ingest.api;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.vitalstream.ingest.Fixtures;
import io.vitalstream.ingest.domain.service.IngestResult;
import io.vitalstream.ingest.domain.service.IngestService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@WebMvcTest(FhirController.class)
@Import(WebTestConfig.class)
class FhirControllerTest {

  private static final MediaType FHIR_JSON = MediaType.parseMediaType("application/fhir+json");

  @Autowired private MockMvc mvc;
  @MockitoBean private IngestService ingest;

  private ResultActions send(MediaType type, String body) throws Exception {
    return mvc.perform(
        post("/api/v1/fhir/Observation")
            .header("Host", "localhost")
            .header("X-Api-Key", "dev-ingest-key")
            .contentType(type)
            .content(body));
  }

  @Test
  void singleObservationIsAccepted() throws Exception {
    when(ingest.ingest(anyList(), anyList())).thenReturn(new IngestResult(1, 0, List.of()));

    send(FHIR_JSON, Fixtures.read("fhir-observation-hr.json"))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.accepted").value(1))
        .andExpect(jsonPath("$.rejected").isEmpty());

    verify(ingest)
        .ingest(argThat(l -> l.size() == 1 && "8867-4".equals(l.get(0).loinc())), anyList());
  }

  @Test
  void plainApplicationJsonIsAlsoAccepted() throws Exception {
    when(ingest.ingest(anyList(), anyList())).thenReturn(new IngestResult(1, 0, List.of()));

    send(MediaType.APPLICATION_JSON, Fixtures.read("fhir-observation-hr.json"))
        .andExpect(status().isAccepted());
  }

  @Test
  void bundleEntriesAreForwardedAndUnsupportedOnesReportedAsAdapterRejections() throws Exception {
    when(ingest.ingest(anyList(), anyList())).thenReturn(new IngestResult(2, 0, List.of()));

    send(FHIR_JSON, Fixtures.read("fhir-bundle.json")).andExpect(status().isAccepted());

    verify(ingest).ingest(argThat(l -> l.size() == 2), argThat(r -> r.size() == 1));
  }

  @Test
  void nonObservationResourceIsA400Problem() throws Exception {
    send(FHIR_JSON, Fixtures.read("fhir-patient.json"))
        .andExpect(status().isBadRequest())
        .andExpect(header().string("Content-Type", "application/problem+json"))
        .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("Patient")));
  }

  @Test
  void garbageIsA400Problem() throws Exception {
    send(FHIR_JSON, "{nope").andExpect(status().isBadRequest());
  }

  @Test
  void xmlIsNotSupported() throws Exception {
    send(MediaType.APPLICATION_XML, "<Observation/>").andExpect(status().isUnsupportedMediaType());
  }
}
