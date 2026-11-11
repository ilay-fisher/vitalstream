package io.vitalstream.ingest.domain;

import static org.assertj.core.api.Assertions.assertThat;

import io.vitalstream.ingest.domain.model.Metric;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class MetricTest {

  @ParameterizedTest
  @CsvSource({
    "HEART_RATE,/min,8867-4,20,250",
    "SPO2,%,59408-5,50,100",
    "RESP_RATE,/min,9279-1,4,60",
    "SYSTOLIC_BP,mm[Hg],8480-6,50,260",
    "DIASTOLIC_BP,mm[Hg],8462-4,20,160",
    "TEMPERATURE,Cel,8310-5,30,43"
  })
  void catalogMatchesContract(String name, String unit, String loinc, double min, double max) {
    Metric metric = Metric.valueOf(name);

    assertThat(metric.unit()).isEqualTo(unit);
    assertThat(metric.loinc()).isEqualTo(loinc);
    assertThat(metric.min()).isEqualTo(min);
    assertThat(metric.max()).isEqualTo(max);
  }

  @Test
  void resolvesByLoinc() {
    assertThat(Metric.fromLoinc("8867-4")).contains(Metric.HEART_RATE);
    assertThat(Metric.fromLoinc("0000-0")).isEmpty();
    assertThat(Metric.fromLoinc(null)).isEmpty();
  }

  @Test
  void resolvesByNameCaseInsensitively() {
    assertThat(Metric.fromName("spo2")).contains(Metric.SPO2);
    assertThat(Metric.fromName("NOPE")).isEmpty();
    assertThat(Metric.fromName(null)).isEmpty();
  }

  @Test
  void rangeIsInclusive() {
    assertThat(Metric.HEART_RATE.accepts(20)).isTrue();
    assertThat(Metric.HEART_RATE.accepts(250)).isTrue();
    assertThat(Metric.HEART_RATE.accepts(19.99)).isFalse();
    assertThat(Metric.HEART_RATE.accepts(250.01)).isFalse();
    assertThat(Metric.HEART_RATE.accepts(Double.NaN)).isFalse();
  }
}
