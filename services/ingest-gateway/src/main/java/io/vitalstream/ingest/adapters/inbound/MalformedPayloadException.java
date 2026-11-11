package io.vitalstream.ingest.adapters.inbound;

/** The request body could not be interpreted at all; the API layer maps this to 400. */
public class MalformedPayloadException extends RuntimeException {

  public MalformedPayloadException(String message) {
    super(message);
  }

  public MalformedPayloadException(String message, Throwable cause) {
    super(message, cause);
  }
}
