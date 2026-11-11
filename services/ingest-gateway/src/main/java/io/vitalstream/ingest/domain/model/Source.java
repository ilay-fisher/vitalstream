package io.vitalstream.ingest.domain.model;

/** Inbound format a reading arrived in; carried on the Kafka event and on metrics. */
public enum Source {
  FHIR,
  HL7V2,
  DEVICE_BATCH
}
