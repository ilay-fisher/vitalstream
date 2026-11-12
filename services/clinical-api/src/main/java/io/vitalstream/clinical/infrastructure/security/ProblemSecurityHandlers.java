package io.vitalstream.clinical.infrastructure.security;

import io.vitalstream.clinical.application.Correlation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

/**
 * Answers authentication and authorization failures as RFC 9457 {@code application/problem+json}
 * with the correlation id, like every other error of this API. 401 carries the {@code
 * WWW-Authenticate: Bearer} challenge. Details never echo the token or why it was rejected, which
 * would only help an attacker; the reason is logged at WARN without the token.
 */
public class ProblemSecurityHandlers implements AuthenticationEntryPoint, AccessDeniedHandler {

  private static final Logger log = LoggerFactory.getLogger(ProblemSecurityHandlers.class);

  private static final String BODY =
      "{\"type\":\"about:blank\",\"title\":\"%s\",\"status\":%d,\"detail\":\"%s\"%s}";

  @Override
  public void commence(
      HttpServletRequest request, HttpServletResponse response, AuthenticationException failure)
      throws IOException {
    boolean badToken = failure instanceof OAuth2AuthenticationException;
    log.warn(
        "Rejected unauthenticated request: {} {} ({})",
        request.getMethod(),
        request.getRequestURI(),
        badToken ? "invalid token" : "no credentials");
    response.setHeader("WWW-Authenticate", badToken ? "Bearer error=\"invalid_token\"" : "Bearer");
    write(
        response,
        HttpServletResponse.SC_UNAUTHORIZED,
        "Unauthorized",
        badToken ? "The bearer token is invalid or expired" : "Authentication required");
  }

  @Override
  public void handle(
      HttpServletRequest request, HttpServletResponse response, AccessDeniedException denied)
      throws IOException {
    log.warn(
        "Rejected request without the required role: {} {}",
        request.getMethod(),
        request.getRequestURI());
    write(
        response,
        HttpServletResponse.SC_FORBIDDEN,
        "Forbidden",
        "Your role does not allow this operation");
  }

  private static void write(HttpServletResponse response, int status, String title, String detail)
      throws IOException {
    String correlationId = MDC.get(Correlation.MDC_KEY);
    String extra = correlationId == null ? "" : ",\"correlationId\":\"" + correlationId + "\"";
    response.setStatus(status);
    response.setContentType("application/problem+json");
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response.getWriter().write(BODY.formatted(title, status, detail, extra));
  }
}
