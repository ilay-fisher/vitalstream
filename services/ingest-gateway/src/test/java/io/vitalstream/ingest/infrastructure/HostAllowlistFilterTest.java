package io.vitalstream.ingest.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import io.vitalstream.ingest.infrastructure.web.HostAllowlistFilter;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class HostAllowlistFilterTest {

  private final HostAllowlistFilter filter =
      new HostAllowlistFilter("localhost,localhost:8081,127.0.0.1:8081,ingest-gateway:8081");

  private MockHttpServletResponse call(String host, AtomicBoolean chainCalled) throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health");
    request.setRequestURI("/actuator/health");
    if (host != null) {
      request.removeHeader("Host");
      request.addHeader("Host", host);
    }
    MockHttpServletResponse response = new MockHttpServletResponse();
    MDC.put("correlationId", "corr-9");
    try {
      filter.doFilter(request, response, (req, res) -> chainCalled.set(true));
    } finally {
      MDC.clear();
    }
    return response;
  }

  @Test
  void allowedHostsPassThrough() throws Exception {
    for (String host :
        new String[] {"localhost", "localhost:8081", "127.0.0.1:8081", "ingest-gateway:8081"}) {
      var called = new AtomicBoolean();
      call(host, called);
      assertThat(called).as(host).isTrue();
    }
  }

  @Test
  void comparisonIgnoresCase() throws Exception {
    var called = new AtomicBoolean();

    call("LocalHost:8081", called);

    assertThat(called).isTrue();
  }

  @Test
  void foreignHostIs400ProblemJson() throws Exception {
    var called = new AtomicBoolean();

    var response = call("evil.example.com", called);

    assertThat(called).isFalse();
    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(response.getContentType()).startsWith("application/problem+json");
    assertThat(response.getContentAsString())
        .contains("urn:vitalstream:problem:invalid-host")
        .contains("\"correlationId\":\"corr-9\"")
        .doesNotContain("evil.example.com");
  }

  @Test
  void allowedHostnameOnUnlistedPortIsRejected() throws Exception {
    var called = new AtomicBoolean();

    call("127.0.0.1:9999", called);

    assertThat(called).isFalse();
  }

  @Test
  void missingHostIsRejected() throws Exception {
    var called = new AtomicBoolean();
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/x");
    request.setRequestURI("/x");
    request.removeHeader("Host");
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, (req, res) -> called.set(true));

    assertThat(called).isFalse();
    assertThat(response.getStatus()).isEqualTo(400);
  }
}
