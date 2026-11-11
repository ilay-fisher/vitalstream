package io.vitalstream.clinical.application;

import java.util.Map;

/** Port: latest reading per metric for a patient (Redis {@code patient:{id}:latest}). */
public interface LatestVitalsStore {

  /** Metric name to latest reading; empty when nothing was received recently. */
  Map<String, LatestVital> latest(String patientId);
}
