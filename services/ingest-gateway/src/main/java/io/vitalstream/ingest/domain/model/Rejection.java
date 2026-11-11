package io.vitalstream.ingest.domain.model;

/**
 * Why a single reading was refused. The machine-readable {@code code} feeds the {@code
 * vitals_rejected_total{reason}} metric (low cardinality); {@code detail} is for the sender.
 */
public record Rejection(String eventId, RejectionReason code, String detail) {

  /** The text returned to the client in the {@code rejected[].reason} field. */
  public String reason() {
    return detail == null || detail.isBlank() ? code.name() : code.name() + ": " + detail;
  }
}
