package io.vitalstream.ingest.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import io.vitalstream.ingest.infrastructure.web.ApiKeyFilter;
import jakarta.servlet.FilterChain;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ApiKeyFilterTest {

  private final ApiKeyFilter filter = new ApiKeyFilter("s3cret-key");

  private MockHttpServletResponse call(String path, String apiKey, AtomicBoolean chainCalled)
      throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
    request.setRequestURI(path);
    if (apiKey != null) {
      request.addHeader("X-Api-Key", apiKey);
    }
    MockHttpServletResponse response = new MockHttpServletResponse();
    FilterChain chain = (req, res) -> chainCalled.set(true);
    MDC.put("correlationId", "corr-1");
    try {
      filter.doFilter(request, response, chain);
    } finally {
      MDC.clear();
    }
    return response;
  }

  @Test
  void validKeyPassesThrough() throws Exception {
    var called = new AtomicBoolean();

    var response = call("/api/v1/vitals/batch", "s3cret-key", called);

    assertThat(called).isTrue();
    assertThat(response.getStatus()).isEqualTo(200);
  }

  @Test
  void missingKeyIs401ProblemJsonWithCorrelationId() throws Exception {
    var called = new AtomicBoolean();

    var response = call("/api/v1/hl7", null, called);

    assertThat(called).isFalse();
    assertThat(response.getStatus()).isEqualTo(401);
    assertThat(response.getContentType()).startsWith("application/problem+json");
    assertThat(response.getContentAsString())
        .contains("\"status\":401")
        .contains("urn:vitalstream:problem:unauthorized")
        .contains("\"correlationId\":\"corr-1\"");
  }

  @Test
  void wrongKeyIsRejectedAndNeverEchoed() throws Exception {
    var called = new AtomicBoolean();

    var response = call("/api/v1/fhir/Observation", "wrong-key", called);

    assertThat(called).isFalse();
    assertThat(response.getStatus()).isEqualTo(401);
    assertThat(response.getContentAsString()).doesNotContain("wrong-key").doesNotContain("s3cret");
  }

  @Test
  void keyOfDifferentLengthIsRejected() throws Exception {
    var called = new AtomicBoolean();

    call("/api/v1/vitals/batch", "s3cret-key-and-more", called);

    assertThat(called).isFalse();
  }

  @Test
  void operationalAndDocumentationPathsStayOpen() throws Exception {
    for (String path :
        new String[] {
          "/actuator/health", "/actuator/prometheus", "/v3/api-docs", "/swagger-ui/index.html"
        }) {
      var called = new AtomicBoolean();
      call(path, null, called);
      assertThat(called).as(path).isTrue();
    }
  }

  @Test
  void emptyConfiguredKeyNeverMatches() throws Exception {
    var emptyKeyFilter = new ApiKeyFilter("");
    var called = new AtomicBoolean();
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/hl7");
    request.setRequestURI("/api/v1/hl7");
    request.addHeader("X-Api-Key", "");
    MockHttpServletResponse response = new MockHttpServletResponse();

    emptyKeyFilter.doFilter(request, response, (req, res) -> called.set(true));

    assertThat(called).isFalse();
    assertThat(response.getStatus()).isEqualTo(401);
  }
}
