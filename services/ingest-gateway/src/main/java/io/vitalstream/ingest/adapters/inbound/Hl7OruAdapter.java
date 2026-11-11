package io.vitalstream.ingest.adapters.inbound;

import ca.uhn.hl7v2.DefaultHapiContext;
import ca.uhn.hl7v2.HL7Exception;
import ca.uhn.hl7v2.HapiContext;
import ca.uhn.hl7v2.model.Message;
import ca.uhn.hl7v2.model.Primitive;
import ca.uhn.hl7v2.model.Type;
import ca.uhn.hl7v2.model.v25.group.ORU_R01_OBSERVATION;
import ca.uhn.hl7v2.model.v25.group.ORU_R01_ORDER_OBSERVATION;
import ca.uhn.hl7v2.model.v25.group.ORU_R01_PATIENT_RESULT;
import ca.uhn.hl7v2.model.v25.message.ORU_R01;
import ca.uhn.hl7v2.model.v25.segment.OBX;
import ca.uhn.hl7v2.parser.CanonicalModelClassFactory;
import ca.uhn.hl7v2.parser.PipeParser;
import ca.uhn.hl7v2.validation.impl.ValidationContextFactory;
import io.vitalstream.ingest.domain.model.RawReading;
import io.vitalstream.ingest.domain.model.Source;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Adapter for HL7 v2.x ORU^R01 observation results. Every OBX becomes one reading: the LOINC code
 * comes from OBX-3, value and unit from OBX-5/6, time from OBX-14 (falling back to OBR-7) and the
 * device from OBX-18 (falling back to the sending application MSH-3). HL7 has no event-id field, so
 * ids are derived from sender, control id (MSH-10) and OBX set id; a retransmitted message
 * therefore produces the same ids and is de-duplicated like any other retry.
 */
public class Hl7OruAdapter implements InboundAdapter<ORU_R01> {

  private static final Pattern HL7_TIMESTAMP =
      Pattern.compile(
          "^(\\d{4})(\\d{2})(\\d{2})(\\d{2})(\\d{2})(\\d{2})?(?:\\.(\\d{1,4}))?([+-]\\d{4})?$");

  private final PipeParser parser;

  public Hl7OruAdapter() {
    HapiContext context = new DefaultHapiContext(new CanonicalModelClassFactory("2.5"));
    context.setValidationContext(ValidationContextFactory.noValidation());
    this.parser = context.getPipeParser();
  }

  /**
   * Parses raw HL7 text. HL7 segments end with CR, but people paste messages with LF or CRLF, so
   * all three are accepted.
   */
  public ORU_R01 parse(String text) {
    if (text == null || text.isBlank()) {
      throw new MalformedPayloadException("Empty HL7 message");
    }
    String normalised = text.strip().replace("\r\n", "\r").replace('\n', '\r');
    Message message;
    try {
      message = parser.parse(normalised);
    } catch (HL7Exception e) {
      throw new MalformedPayloadException("Not a parseable HL7 v2 message: " + e.getMessage(), e);
    }
    if (!(message instanceof ORU_R01 oru)) {
      throw new MalformedPayloadException("Only ORU^R01 is supported but got " + message.getName());
    }
    if (controlId(oru).isBlank()) {
      throw new MalformedPayloadException("MSH-10 message control id is required");
    }
    return oru;
  }

  /** The MSH-10 control id the ACK must echo in MSA-2. */
  public static String controlId(ORU_R01 message) {
    String id = message.getMSH().getMessageControlID().getValue();
    return id == null ? "" : id;
  }

  /** The MSH-3 sending application, which becomes the ACK's receiving application. */
  public static String sendingApplication(ORU_R01 message) {
    String app = message.getMSH().getSendingApplication().getNamespaceID().getValue();
    return app == null ? "" : app;
  }

  @Override
  public AdaptedBatch adapt(ORU_R01 message) {
    String sender =
        sendingApplication(message)
            + "|"
            + nullToEmpty(message.getMSH().getSendingFacility().getNamespaceID().getValue());
    String control = controlId(message);
    List<RawReading> readings = new ArrayList<>();
    int position = 0;

    for (int p = 0; p < message.getPATIENT_RESULTReps(); p++) {
      ORU_R01_PATIENT_RESULT result = message.getPATIENT_RESULT(p);
      String patientId = patientId(result);
      for (int o = 0; o < result.getORDER_OBSERVATIONReps(); o++) {
        ORU_R01_ORDER_OBSERVATION order = result.getORDER_OBSERVATION(o);
        String orderTime = timestamp(order.getOBR().getObservationDateTime().getTime().getValue());
        for (int k = 0; k < order.getOBSERVATIONReps(); k++) {
          ORU_R01_OBSERVATION observation = order.getOBSERVATION(k);
          position++;
          OBX obx = observation.getOBX();
          readings.add(
              map(message, obx, patientId, orderTime, sender + "|" + control + "|" + position));
        }
      }
    }
    PayloadLimits.requireWithinLimit(readings.size());
    return new AdaptedBatch(List.copyOf(readings), List.of());
  }

  private static RawReading map(
      ORU_R01 message, OBX obx, String patientId, String orderTime, String idSeed) {
    String observedAt = timestamp(obx.getDateTimeOfTheObservation().getTime().getValue());
    return new RawReading(
        UUID.nameUUIDFromBytes(idSeed.getBytes(StandardCharsets.UTF_8)).toString(),
        patientId,
        deviceId(message, obx),
        null,
        blankToNull(obx.getObservationIdentifier().getIdentifier().getValue()),
        blankToNull(obx.getUnits().getIdentifier().getValue()),
        numericValue(obx),
        observedAt != null ? observedAt : orderTime,
        Source.HL7V2);
  }

  private static String patientId(ORU_R01_PATIENT_RESULT result) {
    var pid = result.getPATIENT().getPID();
    if (pid.getPatientIdentifierListReps() == 0) {
      return null;
    }
    return blankToNull(pid.getPatientIdentifierList(0).getIDNumber().getValue());
  }

  private static String deviceId(ORU_R01 message, OBX obx) {
    if (obx.getEquipmentInstanceIdentifierReps() > 0) {
      String equipment =
          blankToNull(obx.getEquipmentInstanceIdentifier(0).getEntityIdentifier().getValue());
      if (equipment != null) {
        return equipment;
      }
    }
    return blankToNull(sendingApplication(message));
  }

  private static Double numericValue(OBX obx) {
    if (obx.getObservationValueReps() == 0) {
      return null;
    }
    Type data = obx.getObservationValue(0).getData();
    if (!(data instanceof Primitive primitive) || blankToNull(primitive.getValue()) == null) {
      return null;
    }
    try {
      return Double.valueOf(primitive.getValue().trim());
    } catch (NumberFormatException e) {
      return null;
    }
  }

  /**
   * Converts an HL7 timestamp ({@code YYYYMMDDHHMM[SS[.S+]][+/-ZZZZ]}) to an ISO-8601 instant. A
   * timestamp without an offset is read as UTC. Returns null when the value is absent or less
   * precise than a minute.
   */
  static String timestamp(String hl7) {
    if (hl7 == null) {
      return null;
    }
    Matcher m = HL7_TIMESTAMP.matcher(hl7.trim());
    if (!m.matches()) {
      return null;
    }
    int seconds = m.group(6) == null ? 0 : Integer.parseInt(m.group(6));
    int nanos = m.group(7) == null ? 0 : fractionToNanos(m.group(7));
    ZoneOffset offset = m.group(8) == null ? ZoneOffset.UTC : ZoneOffset.of(m.group(8));
    try {
      LocalDateTime local =
          LocalDateTime.of(
              Integer.parseInt(m.group(1)),
              Integer.parseInt(m.group(2)),
              Integer.parseInt(m.group(3)),
              Integer.parseInt(m.group(4)),
              Integer.parseInt(m.group(5)),
              seconds,
              nanos);
      return Instant.from(OffsetDateTime.of(local, offset)).toString();
    } catch (java.time.DateTimeException e) {
      return null;
    }
  }

  private static int fractionToNanos(String fraction) {
    return Integer.parseInt((fraction + "000000000").substring(0, 9));
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }

  private static String nullToEmpty(String value) {
    return value == null ? "" : value;
  }
}
