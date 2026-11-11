package io.vitalstream.clinical.infrastructure.kafka;

import io.vitalstream.clinical.application.AlertEvent;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Parses and validates raw record bytes. Parsing happens in the listener (not in a Kafka
 * deserializer) so that a bad record reaches the dead-letter topic with its original bytes intact.
 */
@Component
public class AlertEventParser {

  private final JsonMapper mapper;

  public AlertEventParser(JsonMapper mapper) {
    this.mapper = mapper;
  }

  /**
   * @throws PoisonMessageException if the bytes are not a complete AlertEvent
   */
  public AlertEvent parse(byte[] value) {
    if (value == null || value.length == 0) {
      throw new PoisonMessageException("Empty record value");
    }
    AlertEvent event;
    try {
      event = mapper.readValue(value, AlertEvent.class);
    } catch (JacksonException e) {
      throw new PoisonMessageException("Malformed AlertEvent JSON: " + e.getOriginalMessage(), e);
    }
    if (event == null || !event.isComplete()) {
      throw new PoisonMessageException("AlertEvent is missing required fields");
    }
    return event;
  }
}
