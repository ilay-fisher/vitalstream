package io.vitalstream.clinical.application;

import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Serialises the outbox envelope. The correlation id lives inside the payload (not a column) so the
 * relay can restore it into the logs and message headers of the original request.
 */
@Component
public class OutboxPayloads {

  /** JSON stored in {@code outbox.payload}. */
  public record Envelope(String correlationId, AlertView alert) {}

  private final JsonMapper mapper;

  public OutboxPayloads(JsonMapper mapper) {
    this.mapper = mapper;
  }

  public String write(String correlationId, AlertView alert) {
    return mapper.writeValueAsString(new Envelope(correlationId, alert));
  }

  public Envelope read(String payload) {
    return mapper.readValue(payload, Envelope.class);
  }
}
