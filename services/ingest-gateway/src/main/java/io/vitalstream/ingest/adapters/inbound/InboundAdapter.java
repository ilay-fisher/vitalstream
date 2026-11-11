package io.vitalstream.ingest.adapters.inbound;

/**
 * Strategy that translates one external wire format into the common {@code RawReading} shape.
 * Adding a format means adding one implementation; nothing downstream changes.
 *
 * @param <T> the parsed form of the inbound payload
 */
public interface InboundAdapter<T> {

  /**
   * @throws MalformedPayloadException when the payload as a whole cannot be interpreted; problems
   *     with individual readings are reported later, per reading
   */
  AdaptedBatch adapt(T payload);
}
