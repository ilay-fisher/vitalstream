package io.vitalstream.ingest.adapters;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ca.uhn.fhir.context.FhirContext;
import io.vitalstream.ingest.Fixtures;
import io.vitalstream.ingest.adapters.inbound.AdaptedBatch;
import io.vitalstream.ingest.adapters.inbound.FhirAdapter;
import io.vitalstream.ingest.adapters.inbound.MalformedPayloadException;
import io.vitalstream.ingest.domain.model.RawReading;
import io.vitalstream.ingest.domain.model.RejectionReason;
import io.vitalstream.ingest.domain.model.Source;
import org.junit.jupiter.api.Test;

class FhirAdapterTest {

  private final FhirAdapter adapter = new FhirAdapter(FhirContext.forR4Cached());

  @Test
  void mapsSingleObservation() {
    AdaptedBatch batch = adapter.adapt(Fixtures.read("fhir-observation-hr.json"));

    assertThat(batch.rejections()).isEmpty();
    assertThat(batch.readings()).hasSize(1);
    RawReading r = batch.readings().get(0);
    assertThat(r.eventId()).isEqualTo("6f1c2a1e-8d0b-4c11-9a77-3f0c2b9e1d10");
    assertThat(r.patientId()).isEqualTo("P-0001");
    assertThat(r.deviceId()).isEqualTo("DEV-ICU-0001");
    assertThat(r.metric()).isNull();
    assertThat(r.loinc()).isEqualTo("8867-4");
    assertThat(r.unit()).isEqualTo("/min");
    assertThat(r.value()).isEqualTo(118.0);
    assertThat(r.observedAt()).isEqualTo("2026-11-11T20:15:30.123Z");
    assertThat(r.source()).isEqualTo(Source.FHIR);
  }

  @Test
  void mapsBundleEntriesAndRejectsNonObservationResources() {
    AdaptedBatch batch = adapter.adapt(Fixtures.read("fhir-bundle.json"));

    assertThat(batch.readings()).extracting(RawReading::loinc).containsExactly("8867-4", "59408-5");
    assertThat(batch.rejections()).hasSize(1);
    assertThat(batch.rejections().get(0).code()).isEqualTo(RejectionReason.UNSUPPORTED_RESOURCE);
    assertThat(batch.rejections().get(0).detail()).contains("Patient");
  }

  @Test
  void splitsBloodPressurePanelIntoOneReadingPerComponentWithStableDistinctIds() {
    AdaptedBatch first = adapter.adapt(Fixtures.read("fhir-observation-bp-panel.json"));
    AdaptedBatch second = adapter.adapt(Fixtures.read("fhir-observation-bp-panel.json"));

    assertThat(first.readings()).extracting(RawReading::loinc).containsExactly("8480-6", "8462-4");
    assertThat(first.readings()).extracting(RawReading::value).containsExactly(120.0, 80.0);
    assertThat(first.readings())
        .extracting(RawReading::observedAt)
        .containsOnly("2026-11-11T20:15:30Z");
    assertThat(first.readings().get(0).eventId()).isNotEqualTo(first.readings().get(1).eventId());
    assertThat(first.readings().get(0).eventId()).isEqualTo(second.readings().get(0).eventId());
  }

  @Test
  void observationWithoutQuantityBecomesReadingWithNullValue() {
    String json =
        """
        {"resourceType":"Observation","id":"6f1c2a1e-8d0b-4c11-9a77-3f0c2b9e1d10","status":"final",
         "code":{"coding":[{"system":"http://loinc.org","code":"8867-4"}]},
         "subject":{"reference":"Patient/P-0001"},"device":{"reference":"Device/DEV-ICU-0001"}}
        """;

    AdaptedBatch batch = adapter.adapt(json);

    assertThat(batch.readings()).hasSize(1);
    assertThat(batch.readings().get(0).value()).isNull();
    assertThat(batch.readings().get(0).observedAt()).isNull();
  }

  @Test
  void fallsBackToIdentifierWhenIdMissing() {
    String json =
        """
        {"resourceType":"Observation","status":"final",
         "identifier":[{"value":"6f1c2a1e-8d0b-4c11-9a77-3f0c2b9e1d10"}],
         "code":{"coding":[{"system":"http://loinc.org","code":"8867-4"}]},
         "valueQuantity":{"value":80,"code":"/min"}}
        """;

    assertThat(adapter.adapt(json).readings().get(0).eventId())
        .isEqualTo("6f1c2a1e-8d0b-4c11-9a77-3f0c2b9e1d10");
  }

  @Test
  void rejectsPayloadsThatAreNotObservationOrBatchBundle() {
    assertThatThrownBy(() -> adapter.adapt(Fixtures.read("fhir-patient.json")))
        .isInstanceOf(MalformedPayloadException.class)
        .hasMessageContaining("Patient");
    assertThatThrownBy(() -> adapter.adapt(Fixtures.read("fhir-bundle-document.json")))
        .isInstanceOf(MalformedPayloadException.class)
        .hasMessageContaining("batch");
  }

  @Test
  void rejectsUnparseableJson() {
    assertThatThrownBy(() -> adapter.adapt("{not json"))
        .isInstanceOf(MalformedPayloadException.class);
  }
}
