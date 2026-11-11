package io.vitalstream.ingest.api;

import ca.uhn.hl7v2.model.v25.message.ORU_R01;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.vitalstream.ingest.adapters.inbound.AdaptedBatch;
import io.vitalstream.ingest.adapters.inbound.Hl7AckBuilder;
import io.vitalstream.ingest.adapters.inbound.Hl7OruAdapter;
import io.vitalstream.ingest.adapters.inbound.MalformedPayloadException;
import io.vitalstream.ingest.domain.service.IngestResult;
import io.vitalstream.ingest.domain.service.IngestService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** HL7 v2 entry point for legacy bedside monitors and interface engines. */
@RestController
@RequestMapping("/api/v1/hl7")
@Tag(name = "HL7 v2")
public class Hl7Controller {

  private static final Logger log = LoggerFactory.getLogger(Hl7Controller.class);
  private static final MediaType TEXT_PLAIN_UTF8 =
      MediaType.parseMediaType("text/plain;charset=UTF-8");

  private final Hl7OruAdapter adapter;
  private final Hl7AckBuilder acks;
  private final IngestService ingest;

  public Hl7Controller(Hl7OruAdapter adapter, Hl7AckBuilder acks, IngestService ingest) {
    this.adapter = adapter;
    this.acks = acks;
    this.ingest = ingest;
  }

  /**
   * Takes an ORU^R01 and answers with an HL7 ACK in the body. HL7 senders judge success by the
   * ACK's MSA code, not the HTTP status, so an unparseable message still gets a (400) AE
   * acknowledgement instead of a JSON problem document the sender could not interpret.
   */
  @PostMapping(consumes = {MediaType.TEXT_PLAIN_VALUE, "application/hl7-v2"})
  @Operation(summary = "Ingest an HL7 v2.5 ORU^R01 and receive an ACK (AA/AE)")
  public ResponseEntity<String> ingestOru(@RequestBody String body) {
    ORU_R01 message;
    try {
      message = adapter.parse(body);
    } catch (MalformedPayloadException e) {
      log.warn("hl7 message rejected: {}", e.getMessage());
      return reply(HttpStatus.BAD_REQUEST, acks.error(e.getMessage()));
    }
    AdaptedBatch batch = adapter.adapt(message);
    IngestResult result = ingest.ingest(batch.readings(), batch.rejections());
    String ack =
        result.rejected().isEmpty()
            ? acks.accepted(message)
            : acks.rejected(message, result.rejected());
    return reply(HttpStatus.ACCEPTED, ack);
  }

  private static ResponseEntity<String> reply(HttpStatus status, String ack) {
    return ResponseEntity.status(status).contentType(TEXT_PLAIN_UTF8).body(ack);
  }
}
