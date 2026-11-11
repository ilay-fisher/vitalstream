package io.vitalstream.ingest.adapters.inbound;

import ca.uhn.hl7v2.HL7Exception;
import ca.uhn.hl7v2.model.v25.message.ACK;
import ca.uhn.hl7v2.model.v25.message.ORU_R01;
import ca.uhn.hl7v2.model.v25.segment.ERR;
import io.vitalstream.ingest.domain.model.Rejection;
import java.io.IOException;
import java.util.List;

/**
 * Builds HL7 v2.5 ACK messages. HL7 senders expect a protocol-level answer, not just an HTTP
 * status: AA means every OBX was taken (or was a known duplicate), AE means at least one was
 * refused, with an ERR segment per refusal so the sender can see which and why.
 */
public class Hl7AckBuilder {

  private static final String SELF = "INGEST-GATEWAY";
  private static final String UNKNOWN_CONTROL_ID = "UNKNOWN";

  /** AA: application accept. */
  public String accepted(ORU_R01 original) {
    return build(original, "AA", null, List.of());
  }

  /** AE: application error, one ERR segment per rejected reading. */
  public String rejected(ORU_R01 original, List<Rejection> rejections) {
    return build(
        original,
        "AE",
        rejections.size() + " reading(s) rejected",
        rejections.stream().map(Rejection::reason).toList());
  }

  /** AE for a message that could not be parsed, so no control id is known. */
  public String error(String text) {
    return build(null, "AE", text, List.of(text));
  }

  private static String build(
      ORU_R01 original, String code, String text, List<String> diagnostics) {
    try {
      ACK ack = new ACK();
      ack.initQuickstart("ACK", "R01", "P");
      ack.getMSH().getSendingApplication().getNamespaceID().setValue(SELF);
      String controlId = UNKNOWN_CONTROL_ID;
      if (original != null) {
        controlId = Hl7OruAdapter.controlId(original);
        ack.getMSH()
            .getReceivingApplication()
            .getNamespaceID()
            .setValue(Hl7OruAdapter.sendingApplication(original));
      }
      ack.getMSA().getAcknowledgmentCode().setValue(code);
      ack.getMSA().getMessageControlID().setValue(controlId);
      if (text != null) {
        ack.getMSA().getTextMessage().setValue(text);
      }
      for (int i = 0; i < diagnostics.size(); i++) {
        ERR err = ack.getERR(i);
        err.getErrorCodeAndLocation(0).getCodeIdentifyingError().getIdentifier().setValue("102");
        err.getSeverity().setValue("E");
        err.getDiagnosticInformation().setValue(diagnostics.get(i));
      }
      return ack.encode();
    } catch (HL7Exception | IOException e) {
      throw new IllegalStateException("Could not build HL7 ACK", e);
    }
  }
}
