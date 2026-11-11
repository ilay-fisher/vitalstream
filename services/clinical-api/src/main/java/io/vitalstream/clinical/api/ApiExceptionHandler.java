package io.vitalstream.clinical.api;

import com.datastax.oss.driver.api.core.DriverException;
import io.vitalstream.clinical.application.Correlation;
import io.vitalstream.clinical.domain.AlertNotFoundException;
import io.vitalstream.clinical.domain.ConcurrentAlertUpdateException;
import io.vitalstream.clinical.domain.InvalidStatusTransitionException;
import io.vitalstream.clinical.domain.PatientNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.RestClientException;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Maps every failure to an RFC 9457 {@code application/problem+json} body that carries the
 * correlation id, so a client-visible error can be traced to the server log line.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

  @ExceptionHandler({AlertNotFoundException.class, PatientNotFoundException.class})
  ProblemDetail notFound(RuntimeException e) {
    return problem(HttpStatus.NOT_FOUND, "Resource not found", e.getMessage());
  }

  @ExceptionHandler({InvalidStatusTransitionException.class, ConcurrentAlertUpdateException.class})
  ProblemDetail conflict(RuntimeException e) {
    return problem(HttpStatus.CONFLICT, "Conflict", e.getMessage());
  }

  @ExceptionHandler(InvalidRequestException.class)
  ProblemDetail badRequest(InvalidRequestException e) {
    return problem(HttpStatus.BAD_REQUEST, "Invalid request", e.getMessage());
  }

  @ExceptionHandler({RestClientException.class, DriverException.class})
  ProblemDetail dependencyUnavailable(Exception e) {
    log.error("Backing store unavailable", e);
    return problem(
        HttpStatus.SERVICE_UNAVAILABLE,
        "Dependency unavailable",
        "A backing store is temporarily unavailable");
  }

  @ExceptionHandler(Exception.class)
  ProblemDetail unexpected(Exception e) {
    log.error("Unhandled error", e);
    return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error", "Unexpected server error");
  }

  @Override
  protected ResponseEntity<Object> handleExceptionInternal(
      Exception ex,
      Object body,
      HttpHeaders headers,
      HttpStatusCode statusCode,
      WebRequest request) {
    if (body instanceof ProblemDetail detail) {
      addCorrelationId(detail);
    }
    return super.handleExceptionInternal(ex, body, headers, statusCode, request);
  }

  private static ProblemDetail problem(HttpStatus status, String title, String detail) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
    problem.setTitle(title);
    addCorrelationId(problem);
    return problem;
  }

  private static void addCorrelationId(ProblemDetail problem) {
    String correlationId = MDC.get(Correlation.MDC_KEY);
    if (correlationId != null) {
      problem.setProperty("correlationId", correlationId);
    }
  }
}
