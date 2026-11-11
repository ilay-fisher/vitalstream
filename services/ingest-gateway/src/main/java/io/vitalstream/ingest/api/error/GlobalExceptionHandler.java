package io.vitalstream.ingest.api.error;

import io.vitalstream.ingest.adapters.inbound.MalformedPayloadException;
import io.vitalstream.ingest.domain.service.PublishFailedException;
import io.vitalstream.ingest.domain.service.RateLimitExceededException;
import io.vitalstream.ingest.infrastructure.web.CorrelationIdFilter;
import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Turns every failure into an RFC 9457 {@code application/problem+json} body carrying the
 * correlation id, so a failing sender can quote one value to find the matching server log lines.
 * Framework errors (bad JSON, validation, wrong media type) inherit the base class handling and are
 * enriched in {@link #handleExceptionInternal}.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
  private static final String TYPE_BASE = "urn:vitalstream:problem:";

  /** The payload as a whole could not be interpreted (not a single bad reading). */
  @ExceptionHandler(MalformedPayloadException.class)
  ResponseEntity<Object> malformed(MalformedPayloadException e) {
    log.warn("malformed payload: {}", e.getMessage());
    return respond(
        problem(HttpStatus.BAD_REQUEST, "malformed-payload", "Malformed payload", e.getMessage()),
        null);
  }

  /** A device exceeded its token bucket; Retry-After tells it when to come back. */
  @ExceptionHandler(RateLimitExceededException.class)
  ResponseEntity<Object> rateLimited(RateLimitExceededException e) {
    ProblemDetail problem =
        problem(
            HttpStatus.TOO_MANY_REQUESTS,
            "rate-limited",
            "Rate limit exceeded",
            "Device " + e.deviceId() + " exceeded its request budget");
    HttpHeaders headers = new HttpHeaders();
    headers.set(HttpHeaders.RETRY_AFTER, Long.toString(e.retryAfterSeconds()));
    return respond(problem, headers);
  }

  /** Kafka did not acknowledge; nothing was lost because the idempotency keys were released. */
  @ExceptionHandler(PublishFailedException.class)
  ResponseEntity<Object> publishFailed(PublishFailedException e) {
    log.error("publish failed", e);
    return unavailable("event-log-unavailable", "Event log unavailable", e.getMessage());
  }

  /** Redis (idempotency store) is unreachable, so we cannot safely accept the request. */
  @ExceptionHandler(DataAccessException.class)
  ResponseEntity<Object> redisFailure(DataAccessException e) {
    log.error("redis failure", e);
    return unavailable(
        "dependency-unavailable", "Dependency unavailable", "Idempotency store is unavailable");
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<Object> unexpected(Exception e) {
    log.error("unexpected error", e);
    return respond(
        problem(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "internal-error",
            "Internal error",
            "An unexpected error occurred"),
        null);
  }

  @Override
  protected ResponseEntity<Object> handleExceptionInternal(
      Exception ex,
      Object body,
      HttpHeaders headers,
      HttpStatusCode statusCode,
      WebRequest request) {
    ResponseEntity<Object> response =
        super.handleExceptionInternal(ex, body, headers, statusCode, request);
    if (response != null && response.getBody() instanceof ProblemDetail problem) {
      problem.setProperty("correlationId", MDC.get(CorrelationIdFilter.MDC_KEY));
    }
    return response;
  }

  private ResponseEntity<Object> unavailable(String slug, String title, String detail) {
    HttpHeaders headers = new HttpHeaders();
    headers.set(HttpHeaders.RETRY_AFTER, "1");
    return respond(problem(HttpStatus.SERVICE_UNAVAILABLE, slug, title, detail), headers);
  }

  private static ProblemDetail problem(
      HttpStatus status, String slug, String title, String detail) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
    problem.setType(URI.create(TYPE_BASE + slug));
    problem.setTitle(title);
    problem.setProperty("correlationId", MDC.get(CorrelationIdFilter.MDC_KEY));
    return problem;
  }

  private static ResponseEntity<Object> respond(ProblemDetail problem, HttpHeaders headers) {
    ResponseEntity.BodyBuilder builder = ResponseEntity.status(problem.getStatus());
    if (headers != null) {
      builder.headers(headers);
    }
    return builder
        .contentType(org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON)
        .body(problem);
  }
}
