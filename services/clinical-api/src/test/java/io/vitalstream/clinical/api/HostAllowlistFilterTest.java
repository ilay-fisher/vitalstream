package io.vitalstream.clinical.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class HostAllowlistFilterTest {

  private final HostAllowlistFilter filter =
      new HostAllowlistFilter(List.of("localhost", "127.0.0.1", "clinical-api"));

  private MockHttpServletResponse call(HostAllowlistFilter f, String host) throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health");
    request.setLocalPort(8082);
    if (host != null) {
      request.addHeader("Host", host);
    }
    MockHttpServletResponse response = new MockHttpServletResponse();
    f.doFilter(request, response, new MockFilterChain());
    return response;
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "localhost",
        "localhost:8082",
        "127.0.0.1",
        "127.0.0.1:8082",
        "clinical-api",
        "clinical-api:8082",
        "LOCALHOST:8082"
      })
  void defaultHostsAndPortVariantsPass(String host) throws Exception {
    assertThat(call(filter, host).getStatus()).isEqualTo(200);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "evil.example.com",
        "evil.example.com:8082",
        "localhost:9999",
        "localhost.evil.com",
        "127.0.0.2"
      })
  void unknownHostsOrWrongPortsAreRejectedWithProblemJson(String host) throws Exception {
    MockHttpServletResponse response = call(filter, host);

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(response.getContentType()).startsWith("application/problem+json");
    assertThat(response.getContentAsString())
        .contains("\"status\":400")
        .contains("Invalid Host header")
        .doesNotContain(host);
  }

  @Test
  void customAllowlistReplacesTheDefaults() throws Exception {
    HostAllowlistFilter custom =
        new HostAllowlistFilter(List.of(" api.internal ", "10.0.0.5:8082"));

    assertThat(call(custom, "api.internal:8082").getStatus()).isEqualTo(200);
    assertThat(call(custom, "10.0.0.5:8082").getStatus()).isEqualTo(200);
    assertThat(call(custom, "localhost").getStatus()).isEqualTo(400);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/%61pi/v1/patients",
        "/api/v1;x=1/patients",
        "/api/v1/%2e%2e/patients",
        "/actuator/health;a=b"
      })
  void rejectionDoesNotDependOnHowThePathIsSpelled(String uri) throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
    request.setLocalPort(8082);
    request.addHeader("Host", "evil.example.com");
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, new MockFilterChain());

    assertThat(response.getStatus()).isEqualTo(400);
  }
}
