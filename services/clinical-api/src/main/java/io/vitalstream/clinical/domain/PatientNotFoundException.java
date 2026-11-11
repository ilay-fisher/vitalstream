package io.vitalstream.clinical.domain;

/** Raised when a patient id is unknown. */
public class PatientNotFoundException extends RuntimeException {

  public PatientNotFoundException(String id) {
    super("Patient " + id + " not found");
  }
}
