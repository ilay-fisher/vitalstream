package io.vitalstream.clinical.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.vitalstream.clinical.application.AckActor;
import io.vitalstream.clinical.application.AlertAckService;
import io.vitalstream.clinical.application.AlertFilter;
import io.vitalstream.clinical.application.AlertQueryService;
import io.vitalstream.clinical.application.AlertView;
import io.vitalstream.clinical.application.PageResult;
import io.vitalstream.clinical.domain.AlertStatus;
import io.vitalstream.clinical.domain.Severity;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Alert list, detail, acknowledgement and live stream. */
@RestController
@RequestMapping("/api/v1/alerts")
@Tag(name = "Alerts")
public class AlertController {

  private final AlertQueryService queries;
  private final AlertAckService acks;
  private final SseAlertHub hub;

  public AlertController(AlertQueryService queries, AlertAckService acks, SseAlertHub hub) {
    this.queries = queries;
    this.acks = acks;
    this.hub = hub;
  }

  /**
   * Lists alerts newest first. Structured filters hit Postgres; supplying {@code q} switches to
   * Elasticsearch full-text search because relevance ranking is not available in SQL.
   */
  @GetMapping
  @Operation(summary = "List alerts (Postgres, or Elasticsearch when q is present)")
  public PageResult<AlertView> list(
      @RequestParam(required = false) String patientId,
      @RequestParam(required = false) Severity severity,
      @RequestParam(required = false) AlertStatus status,
      @RequestParam(required = false) String q,
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
    return queries.list(new AlertFilter(patientId, severity, status, q, page, size));
  }

  @GetMapping("/{id}")
  @Operation(summary = "Get one alert")
  public AlertView get(@PathVariable UUID id) {
    return queries.get(id);
  }

  /**
   * Acknowledges an OPEN alert on behalf of the token's user (CLINICIAN only). Returns 409 when it
   * was already acknowledged or changed concurrently, so two clinicians never both believe they own
   * the alert.
   */
  @PostMapping("/{id}/ack")
  @Operation(summary = "Acknowledge an alert as the authenticated clinician")
  public AlertView acknowledge(
      @PathVariable UUID id,
      @Valid @RequestBody(required = false) AckRequest request,
      @AuthenticationPrincipal Jwt token) {
    String note = request == null ? null : request.note();
    AckActor actor = AckActor.from(token.getSubject(), token.getClaimAsString("name"));
    return acks.acknowledge(id, actor, note);
  }

  /**
   * Server-Sent Events feed of new and updated alerts (event name {@code alert}). Fed from Redis
   * pub/sub, so it works behind any number of replicas.
   */
  @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  @Operation(summary = "Live alert feed (Server-Sent Events)")
  public SseEmitter stream() {
    return hub.register();
  }
}
