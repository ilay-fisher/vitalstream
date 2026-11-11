package io.vitalstream.clinical.api;

import io.vitalstream.clinical.application.Correlation;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * DNS-rebinding defence: a browser tricked into resolving an attacker's name to 127.0.0.1 still
 * sends the attacker's name in {@code Host}, so anything outside {@code ALLOWED_HOSTS} gets a 400.
 * An entry matches as is, or as a bare name combined with the port the request arrived on, which
 * covers the {@code :8082} variants without listing them. Health endpoints are not exempt.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class HostAllowlistFilter extends OncePerRequestFilter {

  private static final String BODY =
      "{\"type\":\"about:blank\",\"title\":\"Invalid Host header\",\"status\":400,"
          + "\"detail\":\"Host header is not allowed\"%s}";

  private final Set<String> allowed;

  public HostAllowlistFilter(
      @Value("${vitalstream.allowed-hosts:localhost,127.0.0.1,clinical-api}") List<String> hosts) {
    this.allowed =
        hosts.stream()
            .map(h -> h.trim().toLowerCase(Locale.ROOT))
            .filter(h -> !h.isEmpty())
            .collect(Collectors.toUnmodifiableSet());
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if (isAllowed(request)) {
      chain.doFilter(request, response);
      return;
    }
    String correlationId = MDC.get(Correlation.MDC_KEY);
    String extra = correlationId == null ? "" : ",\"correlationId\":\"" + correlationId + "\"";
    response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
    response.setContentType("application/problem+json");
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response.getWriter().write(BODY.formatted(extra));
  }

  private boolean isAllowed(HttpServletRequest request) {
    String raw = request.getHeader("Host");
    if (raw == null) {
      return allowed.contains(request.getServerName().toLowerCase(Locale.ROOT));
    }
    String host = raw.trim().toLowerCase(Locale.ROOT);
    if (allowed.contains(host)) {
      return true;
    }
    int colon = host.lastIndexOf(':');
    if (colon < 0) {
      return false;
    }
    String name = host.substring(0, colon);
    String port = host.substring(colon + 1);
    return allowed.contains(name) && port.equals(String.valueOf(request.getLocalPort()));
  }
}
