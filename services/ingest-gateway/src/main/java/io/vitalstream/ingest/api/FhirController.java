package io.vitalstream.ingest.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.vitalstream.ingest.adapters.inbound.AdaptedBatch;
import io.vitalstream.ingest.adapters.inbound.FhirAdapter;
import io.vitalstream.ingest.api.dto.IngestResponse;
import io.vitalstream.ingest.domain.service.IngestService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** FHIR R4 entry point for EHR and device-integration systems that already speak FHIR. */
@RestController
@RequestMapping("/api/v1/fhir")
@Tag(name = "FHIR R4")
public class FhirController {

  private final FhirAdapter adapter;
  private final IngestService ingest;

  public FhirController(FhirAdapter adapter, IngestService ingest) {
    this.adapter = adapter;
    this.ingest = ingest;
  }

  /**
   * Takes a FHIR Observation or a batch Bundle of Observations. The body is read as raw text and
   * parsed by HAPI rather than by Jackson, because FHIR JSON has rules (choice types such as {@code
   * value[x]}, primitive extensions) that generic data binding gets wrong.
   */
  @PostMapping(
      path = "/Observation",
      consumes = {"application/fhir+json", "application/json"})
  @ResponseStatus(HttpStatus.ACCEPTED)
  @Operation(summary = "Ingest a FHIR R4 Observation or Bundle of Observations")
  public IngestResponse ingestObservation(@RequestBody String body) {
    AdaptedBatch batch = adapter.adapt(body);
    return IngestResponse.from(ingest.ingest(batch.readings(), batch.rejections()));
  }
}
