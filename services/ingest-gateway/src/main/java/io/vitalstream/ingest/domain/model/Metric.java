package io.vitalstream.ingest.domain.model;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * Catalog of the vital signs the platform understands. It is the single place that knows each
 * metric's UCUM unit, LOINC code and physiologically valid range (docs/CONTRACTS.md section 3.1),
 * so every inbound format is validated against the same rules.
 */
public enum Metric {
  HEART_RATE("/min", "8867-4", 20, 250),
  SPO2("%", "59408-5", 50, 100),
  RESP_RATE("/min", "9279-1", 4, 60),
  SYSTOLIC_BP("mm[Hg]", "8480-6", 50, 260),
  DIASTOLIC_BP("mm[Hg]", "8462-4", 20, 160),
  TEMPERATURE("Cel", "8310-5", 30, 43);

  private final String unit;
  private final String loinc;
  private final double min;
  private final double max;

  Metric(String unit, String loinc, double min, double max) {
    this.unit = unit;
    this.loinc = loinc;
    this.min = min;
    this.max = max;
  }

  public String unit() {
    return unit;
  }

  public String loinc() {
    return loinc;
  }

  public double min() {
    return min;
  }

  public double max() {
    return max;
  }

  /** Whether the value is inside the inclusive valid range; NaN and infinities are never valid. */
  public boolean accepts(double value) {
    return value >= min && value <= max;
  }

  public static Optional<Metric> fromLoinc(String loinc) {
    return Arrays.stream(values()).filter(m -> m.loinc.equals(loinc)).findFirst();
  }

  public static Optional<Metric> fromName(String name) {
    if (name == null) {
      return Optional.empty();
    }
    String normalised = name.trim().toUpperCase(Locale.ROOT);
    return Arrays.stream(values()).filter(m -> m.name().equals(normalised)).findFirst();
  }
}
