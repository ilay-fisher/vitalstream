package io.vitalstream.ingest.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.vitalstream.ingest.adapters.inbound.AdaptedBatch;
import io.vitalstream.ingest.adapters.inbound.DeviceBatchAdapter;
import io.vitalstream.ingest.api.dto.DeviceBatchRequest;
import io.vitalstream.ingest.api.dto.IngestResponse;
import io.vitalstream.ingest.domain.service.IngestService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Native JSON entry point used by the bedside-device simulator and gateways we control. */
@RestController
@RequestMapping("/api/v1/vitals")
@Tag(name = "Device batch")
public class VitalsController {

  private final DeviceBatchAdapter adapter;
  private final IngestService ingest;

  public VitalsController(DeviceBatchAdapter adapter, IngestService ingest) {
    this.adapter = adapter;
    this.ingest = ingest;
  }

  /**
   * Accepts up to 500 readings from one device. It answers 202 only after Kafka acknowledged every
   * fresh reading, so "accepted" means durably stored, not merely received.
   */
  @PostMapping(path = "/batch", consumes = MediaType.APPLICATION_JSON_VALUE)
  @ResponseStatus(HttpStatus.ACCEPTED)
  @Operation(summary = "Ingest a batch of device readings")
  public IngestResponse ingestBatch(@Valid @RequestBody DeviceBatchRequest request) {
    AdaptedBatch batch = adapter.adapt(request);
    return IngestResponse.from(ingest.ingest(batch.readings(), batch.rejections()));
  }
}
