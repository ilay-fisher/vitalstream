package io.vitalstream.ingest.domain.service;

/** The broker did not acknowledge one or more readings; the API layer maps this to 503. */
public class PublishFailedException extends RuntimeException {

  public PublishFailedException(String message, Throwable cause) {
    super(message, cause);
  }
}
