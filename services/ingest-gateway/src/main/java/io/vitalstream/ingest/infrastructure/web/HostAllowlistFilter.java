package io.vitalstream.ingest.infrastructure.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Rejects requests whose {@code Host} header is not on the allowlist ({@code ALLOWED_HOSTS}). A
 * loopback-only service can still be reached by a web page via DNS rebinding, which arrives with
 * the attacker's hostname in {@code Host}; refusing unknown hosts closes that route. The rejected
 * value is not echoed back.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
public class HostAllowlistFilter extends OncePerRequestFilter {

  private final Set<String> allowed;

  public HostAllowlistFilter(@Value("${ingest.allowed-hosts}") String allowedHosts) {
    this.allowed =
        Arrays.stream(allowedHosts.split(","))
            .map(h -> h.trim().toLowerCase(Locale.ROOT))
            .filter(h -> !h.isEmpty())
            .collect(Collectors.toUnmodifiableSet());
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String host = request.getHeader("Host");
    if (host != null && allowed.contains(host.trim().toLowerCase(Locale.ROOT))) {
      chain.doFilter(request, response);
      return;
    }
    logger.warn("request rejected: Host header not in allowlist");
    response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
    response
        .getWriter()
        .write(
            "{\"type\":\"urn:vitalstream:problem:invalid-host\",\"title\":\"Invalid Host header\","
                + "\"status\":400,\"detail\":\"The Host header is not allowed\","
                + "\"correlationId\":\""
                + (correlationId == null ? "" : correlationId)
                + "\"}");
  }
}
