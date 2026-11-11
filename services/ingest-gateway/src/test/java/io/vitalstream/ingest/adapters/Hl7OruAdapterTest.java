package io.vitalstream.ingest.adapters;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ca.uhn.hl7v2.model.v25.message.ORU_R01;
import io.vitalstream.ingest.Fixtures;
import io.vitalstream.ingest.adapters.inbound.AdaptedBatch;
import io.vitalstream.ingest.adapters.inbound.Hl7OruAdapter;
import io.vitalstream.ingest.adapters.inbound.MalformedPayloadException;
import io.vitalstream.ingest.domain.model.RawReading;
import io.vitalstream.ingest.domain.model.Source;
import org.junit.jupiter.api.Test;

class Hl7OruAdapterTest {

  private final Hl7OruAdapter adapter = new Hl7OruAdapter();

  @Test
  void mapsEveryObxToAReading() {
    ORU_R01 message = adapter.parse(Fixtures.read("hl7-oru-r01.hl7"));

    AdaptedBatch batch = adapter.adapt(message);

    assertThat(batch.rejections()).isEmpty();
    assertThat(batch.readings()).hasSize(2);
    RawReading hr = batch.readings().get(0);
    assertThat(hr.patientId()).isEqualTo("P-0001");
    assertThat(hr.deviceId()).isEqualTo("DEV-ICU-0001");
    assertThat(hr.loinc()).isEqualTo("8867-4");
    assertThat(hr.metric()).isNull();
    assertThat(hr.unit()).isEqualTo("/min");
    assertThat(hr.value()).isEqualTo(118.0);
    assertThat(hr.observedAt()).isEqualTo("2026-11-11T20:15:30.123Z");
    assertThat(hr.source()).isEqualTo(Source.HL7V2);
    assertThat(batch.readings().get(1).observedAt()).isEqualTo("2026-11-11T20:15:30Z");
    assertThat(batch.readings().get(1).loinc()).isEqualTo("59408-5");
  }

  @Test
  void eventIdsAreDeterministicSoRetriedMessagesDeduplicate() {
    String text = Fixtures.read("hl7-oru-r01.hl7");

    AdaptedBatch a = adapter.adapt(adapter.parse(text));
    AdaptedBatch b = adapter.adapt(adapter.parse(text));

    assertThat(a.readings().get(0).eventId()).isEqualTo(b.readings().get(0).eventId());
    assertThat(a.readings().get(0).eventId()).isNotEqualTo(a.readings().get(1).eventId());
  }

  @Test
  void acceptsCrLfAndCrSegmentSeparators() {
    String text = Fixtures.read("hl7-oru-r01.hl7");

    assertThat(adapter.adapt(adapter.parse(text.replace("\n", "\r\n"))).readings()).hasSize(2);
    assertThat(adapter.adapt(adapter.parse(text.replace("\n", "\r"))).readings()).hasSize(2);
  }

  @Test
  void usesObrTimeAndSendingApplicationWhenObxOmitsThem() {
    String text =
        """
        MSH|^~\\&|DEV-BED-0007|HOSP|VITALSTREAM|VS|20261111201530||ORU^R01^ORU_R01|MSG-0002|P|2.5
        PID|1||P-0002
        OBR|1|||VITALS|||20261111221500+0200
        OBX|1|NM|8310-5^Temp^LN||37.2|Cel
        """;

    RawReading r = adapter.adapt(adapter.parse(text)).readings().get(0);

    assertThat(r.deviceId()).isEqualTo("DEV-BED-0007");
    assertThat(r.observedAt()).isEqualTo("2026-11-11T20:15:00Z");
    assertThat(r.value()).isEqualTo(37.2);
  }

  @Test
  void nonNumericObxValueBecomesNullAndUnparseableTimeBecomesNull() {
    String text =
        """
        MSH|^~\\&|MON|HOSP|VITALSTREAM|VS|20261111201530||ORU^R01^ORU_R01|MSG-0003|P|2.5
        PID|1||P-0001
        OBR|1|||VITALS
        OBX|1|ST|8867-4^HR^LN||high|/min|||||F|||2026||||DEV-ICU-0001
        """;

    RawReading r = adapter.adapt(adapter.parse(text)).readings().get(0);

    assertThat(r.value()).isNull();
    assertThat(r.observedAt()).isNull();
  }

  @Test
  void rejectsMessagesThatAreNotOruR01() {
    String adt = "MSH|^~\\&|A|B|C|D|20261111201530||ADT^A01^ADT_A01|MSG-9|P|2.5\rPID|1||P-0001\r";

    assertThatThrownBy(() -> adapter.parse(adt))
        .isInstanceOf(MalformedPayloadException.class)
        .hasMessageContaining("ORU^R01");
  }

  @Test
  void rejectsGarbageAndMissingControlId() {
    assertThatThrownBy(() -> adapter.parse("hello world"))
        .isInstanceOf(MalformedPayloadException.class);
    assertThatThrownBy(() -> adapter.parse("")).isInstanceOf(MalformedPayloadException.class);
    assertThatThrownBy(() ->
            adapter.parse(
                "MSH|^~\\&|A|B|C|D|20261111201530||ORU^R01^ORU_R01||P|2.5\rPID|1||P-0001\r"))
        .isInstanceOf(MalformedPayloadException.class)
        .hasMessageContaining("MSH-10");
  }
}
