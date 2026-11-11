package io.vitalstream.ingest.adapters.inbound;

import io.vitalstream.ingest.api.dto.DeviceBatchRequest;
import io.vitalstream.ingest.domain.model.RawReading;
import io.vitalstream.ingest.domain.model.Source;
import java.util.List;

/** Adapter for the native JSON device batch: patient and device come from the envelope. */
public class DeviceBatchAdapter implements InboundAdapter<DeviceBatchRequest> {

  @Override
  public AdaptedBatch adapt(DeviceBatchRequest request) {
    PayloadLimits.requireWithinLimit(request.readings().size());
    List<RawReading> readings =
        request.readings().stream()
            .map(r ->
                new RawReading(
                    r.eventId(),
                    request.patientId(),
                    request.deviceId(),
                    r.metric(),
                    null,
                    null,
                    toDouble(r.value()),
                    r.observedAt(),
                    Source.DEVICE_BATCH))
            .toList();
    return new AdaptedBatch(readings, List.of());
  }

  private static Double toDouble(Object value) {
    return value instanceof Number number ? number.doubleValue() : null;
  }
}
