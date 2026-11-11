package io.vitalstream.clinical.domain;

/** Detection rule family that raised the alert (see CONTRACTS section 3.3). */
public enum AlertType {
  THRESHOLD,
  TREND_ZSCORE,
  NEWS2,
  ML_ANOMALY
}
