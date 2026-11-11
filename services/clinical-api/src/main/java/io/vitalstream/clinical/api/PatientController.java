package io.vitalstream.clinical.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.vitalstream.clinical.application.PatientQueryService;
import io.vitalstream.clinical.application.PatientView;
import io.vitalstream.clinical.application.VitalsHistory;
import io.vitalstream.clinical.application.VitalsQuery;
import io.vitalstream.clinical.application.VitalsQueryService;
import io.vitalstream.clinical.domain.VitalMetric;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Patient list/detail with live vitals and historical vitals. */
@RestController
@RequestMapping("/api/v1/patients")
@Tag(name = "Patients")
public class PatientController {

  static final Duration DEFAULT_WINDOW = Duration.ofHours(1);
  static final Duration MAX_WINDOW = Duration.ofDays(7);

  private final PatientQueryService patients;
  private final VitalsQueryService vitals;
  private final Clock clock;

  public PatientController(PatientQueryService patients, VitalsQueryService vitals, Clock clock) {
    this.patients = patients;
    this.vitals = vitals;
    this.clock = clock;
  }

  @GetMapping
  @Operation(summary = "List patients with latest vitals (Redis) and open alert count")
  public List<PatientView> list() {
    return patients.list();
  }

  @GetMapping("/{id}")
  @Operation(summary = "Get a patient with latest vitals")
  public PatientView get(@PathVariable String id) {
    return patients.get(id);
  }

  /**
   * Historical vitals from Cassandra. The range is capped (7 days) because each day is a separate
   * partition; responses are cached in Redis for 10 seconds to absorb dashboard polling.
   */
  @GetMapping("/{id}/vitals")
  @Operation(summary = "Vitals history (Cassandra, cached 10 s in Redis)")
  public VitalsHistory vitals(
      @PathVariable String id,
      @RequestParam(required = false) VitalMetric metric,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          Instant from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          Instant to,
      @RequestParam(defaultValue = "raw") String resolution) {
    Instant end = to != null ? to : clock.instant();
    Instant start = from != null ? from : end.minus(DEFAULT_WINDOW);
    if (!start.isBefore(end)) {
      throw new InvalidRequestException("'from' must be before 'to'");
    }
    if (Duration.between(start, end).compareTo(MAX_WINDOW) > 0) {
      throw new InvalidRequestException("Range must not exceed " + MAX_WINDOW.toDays() + " days");
    }
    return vitals.history(new VitalsQuery(id, metric, start, end, parseResolution(resolution)));
  }

  private static VitalsQuery.Resolution parseResolution(String value) {
    try {
      return VitalsQuery.Resolution.valueOf(value.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new InvalidRequestException("resolution must be 'raw' or 'minute'");
    }
  }
}
