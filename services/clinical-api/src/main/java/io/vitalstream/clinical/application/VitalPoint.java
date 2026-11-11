package io.vitalstream.clinical.application;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

/**
 * One historical data point. Raw points carry {@code value/unit/deviceId}; minute aggregates carry
 * {@code min/max/avg/count}. Unused fields are omitted from the JSON.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record VitalPoint(
    Instant timestamp,
    String metric,
    Double value,
    String unit,
    String deviceId,
    Double min,
    Double max,
    Double avg,
    Integer count) {

  public static VitalPoint raw(
      Instant at, String metric, double value, String unit, String deviceId) {
    return new VitalPoint(at, metric, value, unit, deviceId, null, null, null, null);
  }

  public static VitalPoint minute(
      Instant windowStart, String metric, double min, double max, double avg, int count) {
    return new VitalPoint(windowStart, metric, null, null, null, min, max, avg, count);
  }
}
