package io.vitalstream.clinical.api;

import io.vitalstream.clinical.application.Correlation;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Edge of the correlation chain: adopts the caller's {@code X-Correlation-Id} (or mints one),
 * exposes it to the logs through MDC and echoes it back so clients can quote it in bug reports.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

  private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String incoming = request.getHeader(Correlation.HTTP_HEADER);
    String id =
        incoming != null && SAFE_ID.matcher(incoming).matches()
            ? incoming
            : UUID.randomUUID().toString();
    MDC.put(Correlation.MDC_KEY, id);
    response.setHeader(Correlation.HTTP_HEADER, id);
    try {
      chain.doFilter(request, response);
    } finally {
      MDC.remove(Correlation.MDC_KEY);
    }
  }
}
