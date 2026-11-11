package io.vitalstream.ingest.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import io.vitalstream.ingest.infrastructure.web.CorrelationIdFilter;
import jakarta.servlet.FilterChain;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class CorrelationIdFilterTest {

  private final CorrelationIdFilter filter = new CorrelationIdFilter();

  private String run(String headerValue, AtomicReference<String> seenInChain) throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    if (headerValue != null) {
      request.addHeader("X-Correlation-Id", headerValue);
    }
    MockHttpServletResponse response = new MockHttpServletResponse();
    FilterChain chain = (req, res) -> seenInChain.set(MDC.get("correlationId"));

    filter.doFilter(request, response, chain);

    return response.getHeader("X-Correlation-Id");
  }

  @Test
  void reusesSuppliedIdInMdcAndResponse() throws Exception {
    var seen = new AtomicReference<String>();

    String echoed = run("req-42", seen);

    assertThat(echoed).isEqualTo("req-42");
    assertThat(seen.get()).isEqualTo("req-42");
  }

  @Test
  void generatesIdWhenMissing() throws Exception {
    var seen = new AtomicReference<String>();

    String echoed = run(null, seen);

    assertThat(echoed).isNotBlank();
    assertThat(seen.get()).isEqualTo(echoed);
  }

  @Test
  void replacesUnsafeValuesToPreventLogInjection() throws Exception {
    var seen = new AtomicReference<String>();

    String echoed = run("bad value\nwith newline", seen);

    assertThat(echoed).doesNotContain("\n").doesNotContain(" ");
    assertThat(seen.get()).isEqualTo(echoed);
  }

  @Test
  void replacesOverlongValues() throws Exception {
    String echoed = run("a".repeat(65), new AtomicReference<>());

    assertThat(echoed).hasSizeLessThanOrEqualTo(64);
  }

  @Test
  void clearsMdcAfterRequest() throws Exception {
    run("req-1", new AtomicReference<>());

    assertThat(MDC.get("correlationId")).isNull();
  }
}
