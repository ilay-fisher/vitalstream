package io.vitalstream.ingest.infrastructure.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Guards {@code /api/v1/**} with a shared {@code X-Api-Key} (CONTRACTS section 4.1). The comparison
 * is constant-time so response timing does not leak how much of a guess was right, and the supplied
 * key is never logged or echoed. Health, metrics and API docs stay open for orchestrators and
 * developers.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class ApiKeyFilter extends OncePerRequestFilter {

  public static final String HEADER = "X-Api-Key";
  private static final String PROTECTED_PREFIX = "/api/v1/";

  private final byte[] expected;

  public ApiKeyFilter(@Value("${ingest.api-key}") String apiKey) {
    this.expected = apiKey.getBytes(StandardCharsets.UTF_8);
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !request.getRequestURI().startsWith(PROTECTED_PREFIX);
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String supplied = request.getHeader(HEADER);
    boolean valid =
        expected.length > 0
            && supplied != null
            && MessageDigest.isEqual(expected, supplied.getBytes(StandardCharsets.UTF_8));
    if (valid) {
      chain.doFilter(request, response);
      return;
    }
    logger.warn("request rejected: missing or invalid API key");
    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    response.setHeader("WWW-Authenticate", "ApiKey realm=\"ingest-gateway\"");
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
    response
        .getWriter()
        .write(
            "{\"type\":\"urn:vitalstream:problem:unauthorized\",\"title\":\"Unauthorized\","
                + "\"status\":401,\"detail\":\"A valid X-Api-Key header is required\","
                + "\"correlationId\":\""
                + (correlationId == null ? "" : correlationId)
                + "\"}");
  }
}
