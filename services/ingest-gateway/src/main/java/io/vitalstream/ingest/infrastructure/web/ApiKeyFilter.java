package io.vitalstream.ingest.infrastructure.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Locale;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

/**
 * Guards the whole application with a shared {@code X-Api-Key} (CONTRACTS section 4.1) and fails
 * closed: every request needs the key unless its path is on a short allowlist of open endpoints
 * (health, metrics, API docs).
 *
 * <p>Two earlier weaknesses are closed here. First, deciding "is this protected?" from the raw
 * request URI is unsafe because Spring MVC routes on the decoded, normalised path: {@code
 * /api/v1;x=1/...} or {@code /%61pi/v1/...} did not start with {@code /api/v1/} yet were routed to
 * the controllers. So the allowlist is matched against the decoded path with matrix parameters
 * removed, and anything not on it needs the key. Second, a raw URI containing path parameters, dot
 * segments, empty segments or encoded separators/dots/percent signs is ambiguous (different
 * components may read it differently), so it is refused with 400 outright.
 *
 * <p>The key comparison is constant-time and the supplied key is never logged or echoed.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class ApiKeyFilter extends OncePerRequestFilter {

  public static final String HEADER = "X-Api-Key";

  /** The only paths served without a key; everything else, including unknown paths, needs it. */
  private static final List<String> OPEN_PATHS =
      List.of(
          "/actuator/health",
          "/actuator/health/**",
          "/actuator/prometheus",
          "/v3/api-docs",
          "/v3/api-docs/**",
          "/swagger-ui.html",
          "/swagger-ui/**");

  private static final AntPathMatcher MATCHER = new AntPathMatcher();

  private final UrlPathHelper pathHelper = new UrlPathHelper();
  private final byte[] expected;

  public ApiKeyFilter(@Value("${ingest.api-key}") String apiKey) {
    this.expected = apiKey.getBytes(StandardCharsets.UTF_8);
    this.pathHelper.setUrlDecode(true);
    this.pathHelper.setRemoveSemicolonContent(true);
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if (isAmbiguous(request.getRequestURI())) {
      logger.warn("request rejected: ambiguous request URI");
      problem(
          response,
          HttpServletResponse.SC_BAD_REQUEST,
          "invalid-path",
          "Invalid path",
          "The request path contains characters that are not allowed");
      return;
    }
    if (isOpen(request) || hasValidKey(request)) {
      chain.doFilter(request, response);
      return;
    }
    logger.warn("request rejected: missing or invalid API key");
    response.setHeader("WWW-Authenticate", "ApiKey realm=\"ingest-gateway\"");
    problem(
        response,
        HttpServletResponse.SC_UNAUTHORIZED,
        "unauthorized",
        "Unauthorized",
        "A valid X-Api-Key header is required");
  }

  private boolean isOpen(HttpServletRequest request) {
    String path = pathHelper.getPathWithinApplication(request);
    return OPEN_PATHS.stream().anyMatch(pattern -> MATCHER.match(pattern, path));
  }

  private boolean hasValidKey(HttpServletRequest request) {
    String supplied = request.getHeader(HEADER);
    return expected.length > 0
        && supplied != null
        && MessageDigest.isEqual(expected, supplied.getBytes(StandardCharsets.UTF_8));
  }

  /**
   * True for URIs the application never produces and different layers could read differently: path
   * parameters ({@code ;}), empty or dot segments, backslashes, and percent-encoded separators,
   * dots or percent signs.
   */
  static boolean isAmbiguous(String rawUri) {
    if (rawUri == null) {
      return true;
    }
    String lower = rawUri.toLowerCase(Locale.ROOT);
    return rawUri.indexOf(';') >= 0
        || rawUri.indexOf('\\') >= 0
        || rawUri.contains("//")
        || rawUri.contains("/./")
        || rawUri.contains("/../")
        || rawUri.endsWith("/.")
        || rawUri.endsWith("/..")
        || lower.contains("%2f")
        || lower.contains("%5c")
        || lower.contains("%2e")
        || lower.contains("%25")
        || lower.contains("%00");
  }

  private static void problem(
      HttpServletResponse response, int status, String slug, String title, String detail)
      throws IOException {
    response.setStatus(status);
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
    response
        .getWriter()
        .write(
            "{\"type\":\"urn:vitalstream:problem:"
                + slug
                + "\",\"title\":\""
                + title
                + "\",\"status\":"
                + status
                + ",\"detail\":\""
                + detail
                + "\",\"correlationId\":\""
                + (correlationId == null ? "" : correlationId)
                + "\"}");
  }
}
