package io.vitalstream.clinical.api;

/** Raised for syntactically valid requests whose parameters make no sense together. */
public class InvalidRequestException extends RuntimeException {

  public InvalidRequestException(String message) {
    super(message);
  }
}
