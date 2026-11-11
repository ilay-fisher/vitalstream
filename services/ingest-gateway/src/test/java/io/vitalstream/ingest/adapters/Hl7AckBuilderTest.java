package io.vitalstream.ingest.adapters;

import static org.assertj.core.api.Assertions.assertThat;

import ca.uhn.hl7v2.model.v25.message.ORU_R01;
import io.vitalstream.ingest.Fixtures;
import io.vitalstream.ingest.adapters.inbound.Hl7AckBuilder;
import io.vitalstream.ingest.adapters.inbound.Hl7OruAdapter;
import io.vitalstream.ingest.domain.model.Rejection;
import io.vitalstream.ingest.domain.model.RejectionReason;
import java.util.List;
import org.junit.jupiter.api.Test;

class Hl7AckBuilderTest {

  private final Hl7OruAdapter adapter = new Hl7OruAdapter();
  private final Hl7AckBuilder acks = new Hl7AckBuilder();
  private final ORU_R01 message = adapter.parse(Fixtures.read("hl7-oru-r01.hl7"));

  @Test
  void successfulMessageIsAcknowledgedWithAa() {
    String ack = acks.accepted(message);

    assertThat(ack).startsWith("MSH|^~\\&|INGEST-GATEWAY|");
    assertThat(ack).contains("|ACK^R01^ACK|");
    assertThat(ack).contains("MSA|AA|MSG-0001");
    assertThat(ack).doesNotContain("ERR|");
  }

  @Test
  void rejectedReadingsProduceAeWithOneErrSegmentEach() {
    List<Rejection> rejections =
        List.of(
            new Rejection("a", RejectionReason.OUT_OF_RANGE, "HEART_RATE 999.0 outside [20, 250]"),
            new Rejection("b", RejectionReason.UNIT_MISMATCH, "wrong unit"));

    String ack = acks.rejected(message, rejections);

    assertThat(ack).contains("MSA|AE|MSG-0001");
    assertThat(ack.split("\r")).filteredOn(s -> s.startsWith("ERR|")).hasSize(2);
    assertThat(ack).contains("OUT_OF_RANGE").contains("UNIT_MISMATCH");
  }

  @Test
  void unparseableInputGetsAeWithUnknownControlId() {
    String ack = acks.error("MSH-10 message control id is required");

    assertThat(ack).contains("MSA|AE|UNKNOWN");
    assertThat(ack).contains("MSH-10 message control id is required");
  }

  @Test
  void acknowledgementEchoesSenderAsReceiver() {
    assertThat(acks.accepted(message)).contains("|MONITOR-ICU|");
  }

  @Test
  void buildingAcksDoesNotWriteAnIdFileIntoTheWorkingDirectory() throws Exception {
    java.nio.file.Path idFile = java.nio.file.Path.of("id_file");
    java.nio.file.Files.deleteIfExists(idFile);

    String first = acks.accepted(message);
    String second = acks.accepted(message);

    assertThat(idFile).doesNotExist();
    assertThat(first)
        .matches("(?s)MSH\\|\\^~\\\\&\\|INGEST-GATEWAY\\|\\|MONITOR-ICU\\|\\|\\d{14}.*");
    assertThat(controlId(first)).isNotBlank().isNotEqualTo(controlId(second));
    assertThat(first).contains("|P|2.5");
  }

  private static String controlId(String ack) {
    return ack.split("\r")[0].split("\\|")[9];
  }
}
