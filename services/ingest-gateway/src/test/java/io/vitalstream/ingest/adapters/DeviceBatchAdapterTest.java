package io.vitalstream.ingest.adapters;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.vitalstream.ingest.Fixtures;
import io.vitalstream.ingest.adapters.inbound.AdaptedBatch;
import io.vitalstream.ingest.adapters.inbound.DeviceBatchAdapter;
import io.vitalstream.ingest.adapters.inbound.MalformedPayloadException;
import io.vitalstream.ingest.api.dto.DeviceBatchRequest;
import io.vitalstream.ingest.domain.model.RawReading;
import io.vitalstream.ingest.domain.model.Source;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class DeviceBatchAdapterTest {

  private final DeviceBatchAdapter adapter = new DeviceBatchAdapter();
  private final JsonMapper json = JsonMapper.builder().build();

  @Test
  void mapsEveryReadingWithTopLevelPatientAndDevice() throws Exception {
    DeviceBatchRequest request =
        json.readValue(Fixtures.read("batch.json"), DeviceBatchRequest.class);

    AdaptedBatch batch = adapter.adapt(request);

    assertThat(batch.rejections()).isEmpty();
    assertThat(batch.readings()).hasSize(3);
    RawReading first = batch.readings().get(0);
    assertThat(first.eventId()).isEqualTo("6f1c2a1e-8d0b-4c11-9a77-3f0c2b9e1d10");
    assertThat(first.patientId()).isEqualTo("P-0001");
    assertThat(first.deviceId()).isEqualTo("DEV-ICU-0001");
    assertThat(first.metric()).isEqualTo("HEART_RATE");
    assertThat(first.value()).isEqualTo(118.0);
    assertThat(first.observedAt()).isEqualTo("2026-11-11T20:15:30.123Z");
    assertThat(first.source()).isEqualTo(Source.DEVICE_BATCH);
  }

  @Test
  void integerValuesAreWidenedAndNonNumericValuesBecomeNull() throws Exception {
    DeviceBatchRequest request =
        json.readValue(Fixtures.read("batch.json"), DeviceBatchRequest.class);

    AdaptedBatch batch = adapter.adapt(request);

    assertThat(batch.readings().get(1).value()).isEqualTo(97.0);
    assertThat(batch.readings().get(2).value()).isNull();
  }

  @Test
  void rejectsOversizedBatches() {
    List<DeviceBatchRequest.Reading> readings =
        new ArrayList<>(
            Collections.nCopies(501, new DeviceBatchRequest.Reading("x", "SPO2", 97, "t")));
    DeviceBatchRequest request = new DeviceBatchRequest("DEV-ICU-0001", "P-0001", readings);

    assertThatThrownBy(() -> adapter.adapt(request)).isInstanceOf(MalformedPayloadException.class);
  }
}
