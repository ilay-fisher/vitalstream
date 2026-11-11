package io.vitalstream.ingest.api;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.vitalstream.ingest.Fixtures;
import io.vitalstream.ingest.domain.model.Rejection;
import io.vitalstream.ingest.domain.model.RejectionReason;
import io.vitalstream.ingest.domain.service.IngestResult;
import io.vitalstream.ingest.domain.service.IngestService;
import io.vitalstream.ingest.domain.service.RateLimitExceededException;
import java.util.List;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@WebMvcTest(Hl7Controller.class)
@Import(WebTestConfig.class)
class Hl7ControllerTest {

  @Autowired private MockMvc mvc;
  @MockitoBean private IngestService ingest;

  private ResultActions send(String body) throws Exception {
    return mvc.perform(
        post("/api/v1/hl7")
            .header("Host", "localhost")
            .header("X-Api-Key", "dev-ingest-key")
            .contentType(MediaType.TEXT_PLAIN)
            .content(body));
  }

  @Test
  void acceptedMessageGetsAaAck() throws Exception {
    when(ingest.ingest(anyList(), anyList())).thenReturn(new IngestResult(2, 0, List.of()));

    send(Fixtures.read("hl7-oru-r01.hl7"))
        .andExpect(status().isAccepted())
        .andExpect(header().string("Content-Type", Matchers.startsWith("text/plain")))
        .andExpect(content().string(Matchers.containsString("MSA|AA|MSG-0001")));

    verify(ingest).ingest(org.mockito.ArgumentMatchers.argThat(l -> l.size() == 2), anyList());
  }

  @Test
  void rejectedReadingGetsAeAckWithErrSegment() throws Exception {
    when(ingest.ingest(anyList(), anyList()))
        .thenReturn(
            new IngestResult(
                1,
                0,
                List.of(
                    new Rejection(
                        "x", RejectionReason.OUT_OF_RANGE, "HEART_RATE 999 out of range"))));

    send(Fixtures.read("hl7-oru-r01.hl7"))
        .andExpect(status().isAccepted())
        .andExpect(content().string(Matchers.containsString("MSA|AE|MSG-0001")))
        .andExpect(content().string(Matchers.containsString("OUT_OF_RANGE")));
  }

  @Test
  void duplicatesStillAcknowledgeWithAa() throws Exception {
    when(ingest.ingest(anyList(), anyList())).thenReturn(new IngestResult(0, 2, List.of()));

    send(Fixtures.read("hl7-oru-r01.hl7"))
        .andExpect(status().isAccepted())
        .andExpect(content().string(Matchers.containsString("MSA|AA|MSG-0001")));
  }

  @Test
  void unparseableMessageGets400WithAeAck() throws Exception {
    send("this is not hl7")
        .andExpect(status().isBadRequest())
        .andExpect(header().string("Content-Type", Matchers.startsWith("text/plain")))
        .andExpect(content().string(Matchers.containsString("MSA|AE|UNKNOWN")));
  }

  @Test
  void rateLimitIsStillAProblemDocumentWithRetryAfter() throws Exception {
    when(ingest.ingest(anyList(), anyList()))
        .thenThrow(new RateLimitExceededException("DEV-ICU-0001", 2));

    send(Fixtures.read("hl7-oru-r01.hl7"))
        .andExpect(status().isTooManyRequests())
        .andExpect(header().string("Retry-After", "2"))
        .andExpect(header().string("Content-Type", "application/problem+json"));
  }

  @Test
  void jsonBodyIsRejectedAsUnsupportedMediaType() throws Exception {
    mvc.perform(
            post("/api/v1/hl7")
                .header("Host", "localhost")
                .header("X-Api-Key", "dev-ingest-key")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isUnsupportedMediaType());
  }
}
