package io.vitalstream.ingest.adapters.inbound;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.model.api.TemporalPrecisionEnum;
import ca.uhn.fhir.parser.DataFormatException;
import io.vitalstream.ingest.domain.model.RawReading;
import io.vitalstream.ingest.domain.model.Rejection;
import io.vitalstream.ingest.domain.model.RejectionReason;
import io.vitalstream.ingest.domain.model.Source;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.DateTimeType;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Quantity;
import org.hl7.fhir.r4.model.Reference;

/**
 * Adapter for FHIR R4. Accepts a single {@code Observation} or a {@code Bundle} of type
 * batch/collection. Patient and device come from {@code subject} / {@code device} references
 * ({@code Patient/P-0001}), the metric from the LOINC coding and the value from {@code
 * valueQuantity}. Panels such as blood pressure (LOINC 85354-9) are split into one reading per
 * component, with event ids derived deterministically from the parent id so retries deduplicate.
 */
public class FhirAdapter implements InboundAdapter<String> {

  private static final String LOINC_SYSTEM = "http://loinc.org";

  private final FhirContext fhir;

  public FhirAdapter(FhirContext fhir) {
    this.fhir = fhir;
  }

  @Override
  public AdaptedBatch adapt(String json) {
    IBaseResource resource;
    try {
      resource = fhir.newJsonParser().parseResource(json);
    } catch (DataFormatException e) {
      throw new MalformedPayloadException("Body is not valid FHIR R4 JSON: " + e.getMessage(), e);
    }

    List<RawReading> readings = new ArrayList<>();
    List<Rejection> rejections = new ArrayList<>();
    if (resource instanceof Observation observation) {
      readings.addAll(map(observation));
    } else if (resource instanceof Bundle bundle) {
      adaptBundle(bundle, readings, rejections);
    } else {
      throw new MalformedPayloadException(
          "Expected an Observation or a Bundle but got " + resource.fhirType());
    }
    PayloadLimits.requireWithinLimit(readings.size() + rejections.size());
    return new AdaptedBatch(List.copyOf(readings), List.copyOf(rejections));
  }

  private static void adaptBundle(
      Bundle bundle, List<RawReading> readings, List<Rejection> rejections) {
    if (bundle.getType() != Bundle.BundleType.BATCH
        && bundle.getType() != Bundle.BundleType.COLLECTION) {
      throw new MalformedPayloadException("Bundle.type must be batch (or collection)");
    }
    for (Bundle.BundleEntryComponent entry : bundle.getEntry()) {
      if (entry.getResource() instanceof Observation observation) {
        readings.addAll(map(observation));
      } else {
        String type = entry.getResource() == null ? "none" : entry.getResource().fhirType();
        String id =
            entry.getResource() == null ? null : entry.getResource().getIdElement().getIdPart();
        rejections.add(
            new Rejection(
                id, RejectionReason.UNSUPPORTED_RESOURCE, "Bundle entry of type " + type));
      }
    }
  }

  private static List<RawReading> map(Observation obs) {
    String eventId = eventId(obs);
    String patientId = referenceId(obs.getSubject());
    String deviceId = referenceId(obs.getDevice());
    String observedAt = observedAt(obs);

    if (!obs.hasValueQuantity() && obs.hasComponent()) {
      List<RawReading> readings = new ArrayList<>();
      for (Observation.ObservationComponentComponent component : obs.getComponent()) {
        String loinc = loinc(component.getCode());
        readings.add(
            reading(
                derivedId(eventId, loinc),
                patientId,
                deviceId,
                loinc,
                component.getValueQuantity(),
                observedAt));
      }
      return readings;
    }
    return List.of(
        reading(
            eventId,
            patientId,
            deviceId,
            loinc(obs.getCode()),
            obs.hasValueQuantity() ? obs.getValueQuantity() : null,
            observedAt));
  }

  private static RawReading reading(
      String eventId,
      String patientId,
      String deviceId,
      String loinc,
      Quantity quantity,
      String observedAt) {
    Double value = null;
    String unit = null;
    if (quantity != null && quantity.getValue() != null) {
      BigDecimal number = quantity.getValue();
      value = number.doubleValue();
      unit = quantity.getCode();
    }
    return new RawReading(
        eventId, patientId, deviceId, null, loinc, unit, value, observedAt, Source.FHIR);
  }

  private static String eventId(Observation obs) {
    String id = obs.getIdElement().getIdPart();
    if (id != null && !id.isBlank()) {
      return id;
    }
    return obs.getIdentifier().stream()
        .map(i -> i.getValue())
        .filter(v -> v != null && !v.isBlank())
        .findFirst()
        .orElse(null);
  }

  private static String derivedId(String parentId, String loinc) {
    if (parentId == null) {
      return null;
    }
    return UUID.nameUUIDFromBytes((parentId + "|" + loinc).getBytes(StandardCharsets.UTF_8))
        .toString();
  }

  private static String referenceId(Reference reference) {
    if (reference == null || !reference.hasReference()) {
      return null;
    }
    String ref = reference.getReference();
    return ref.substring(ref.lastIndexOf('/') + 1);
  }

  private static String loinc(CodeableConcept concept) {
    for (Coding coding : concept.getCoding()) {
      if (LOINC_SYSTEM.equals(coding.getSystem())) {
        return coding.getCode();
      }
    }
    return null;
  }

  private static String observedAt(Observation obs) {
    if (!obs.hasEffectiveDateTimeType()) {
      return null;
    }
    DateTimeType effective = obs.getEffectiveDateTimeType();
    if (effective.getValue() == null
        || effective.getPrecision().ordinal() < TemporalPrecisionEnum.SECOND.ordinal()) {
      return null;
    }
    return effective.getValue().toInstant().toString();
  }
}
