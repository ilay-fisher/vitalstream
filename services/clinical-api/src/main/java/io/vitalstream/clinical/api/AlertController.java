package io.vitalstream.clinical.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.vitalstream.clinical.application.AckActor;
import io.vitalstream.clinical.application.AlertAckService;
import io.vitalstream.clinical.application.AlertFilter;
import io.vitalstream.clinical.application.AlertQueryService;
import io.vitalstream.clinical.application.AlertView;
import io.vitalstream.clinical.application.PageResult;
import io.vitalstream.clinical.domain.AlertStatus;
import io.vitalstream.clinical.domain.AlertType;
import io.vitalstream.clinical.domain.Severity;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
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

/** Alert episode list, detail, acknowledgement and live stream. */
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
   * Lists alert <em>episodes</em>, most recently seen first. Structured filters hit Postgres;
   * supplying {@code q} switches to Elasticsearch full-text search because relevance ranking is not
   * available in SQL. {@code topLevel=true} hides findings grouped under a NEWS2 episode, and
   * {@code parentAlertId} lists such a group's children, so a client can show one row per
   * deterioration and expand it on demand.
   */
  @GetMapping
  @Operation(
      summary = "List alert episodes (Postgres, or Elasticsearch when q is present)",
      description =
          "Sorted by lastSeenAt descending. status is repeatable "
              + "(status=OPEN&status=ACKNOWLEDGED). topLevel=true returns only episodes without "
              + "parentAlertId; topLevel=false or absent does not filter. parentAlertId returns "
              + "that episode's children.")
  public PageResult<AlertView> list(
      @RequestParam(required = false) String patientId,
      @RequestParam(required = false) Severity severity,
      @Parameter(description = "Repeatable; any of OPEN, ACKNOWLEDGED, RESOLVED")
          @RequestParam(required = false)
          List<AlertStatus> status,
      @Parameter(description = "Detector family") @RequestParam(required = false) AlertType type,
      @Parameter(description = "true: only episodes without a parent")
          @RequestParam(required = false)
          Boolean topLevel,
      @Parameter(description = "Only the children of this episode") @RequestParam(required = false)
          UUID parentAlertId,
      @RequestParam(required = false) String q,
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
    return queries.list(
        new AlertFilter(
            patientId,
            severity,
            status,
            type,
            Boolean.TRUE.equals(topLevel),
            parentAlertId,
            q,
            page,
            size));
  }

  @GetMapping("/{id}")
  @Operation(summary = "Get one alert episode")
  public AlertView get(@PathVariable UUID id) {
    return queries.get(id);
  }

  /**
   * Acknowledges an OPEN episode on behalf of the token's user (CLINICIAN only). Returns 409 when
   * it is already acknowledged or resolved, so two clinicians never both believe they own the
   * alert. A version clash with a concurrent ingest update is retried server-side first. A later
   * escalation re-opens the episode.
   */
  @PostMapping("/{id}/ack")
  @Operation(
      summary = "Acknowledge an alert episode as the authenticated clinician",
      responses = {
        @ApiResponse(responseCode = "200", description = "Acknowledged"),
        @ApiResponse(responseCode = "409", description = "Already acknowledged or resolved")
      })
  public AlertView acknowledge(
      @PathVariable UUID id,
      @Valid @RequestBody(required = false) AckRequest request,
      @AuthenticationPrincipal Jwt token) {
    String note = request == null ? null : request.note();
    AckActor actor = AckActor.from(token.getSubject(), token.getClaimAsString("name"));
    return acks.acknowledge(id, actor, note);
  }

  /**
   * Server-Sent Events feed of episode changes (event name {@code alert}, id {@code
   * alertId:version}): open, update, escalation, acknowledgement, resolution and link/unlink.
   * Clients upsert by {@code alertId}. Fed from Redis pub/sub, so it works behind any number of
   * replicas.
   */
  @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  @Operation(summary = "Live alert episode feed (Server-Sent Events)")
  public SseEmitter stream() {
    return hub.register();
  }
}
