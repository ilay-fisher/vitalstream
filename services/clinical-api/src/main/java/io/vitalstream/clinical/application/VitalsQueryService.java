package io.vitalstream.clinical.application;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** Cache-aside read of vitals history: Redis first, Cassandra on a miss. */
@Service
public class VitalsQueryService {

  private final VitalsHistoryReader reader;
  private final VitalsCache cache;
  private final PatientQueryService patients;
  private final MeterRegistry meters;

  public VitalsQueryService(
      VitalsHistoryReader reader,
      VitalsCache cache,
      PatientQueryService patients,
      MeterRegistry meters) {
    this.reader = reader;
    this.cache = cache;
    this.patients = patients;
    this.meters = meters;
  }

  public VitalsHistory history(VitalsQuery query) {
    patients.requireExists(query.patientId());
    Optional<VitalsHistory> cached = cache.get(query);
    if (cached.isPresent()) {
      meters.counter("cache.requests", "cache", "vitals", "result", "hit").increment();
      return cached.get();
    }
    meters.counter("cache.requests", "cache", "vitals", "result", "miss").increment();
    List<VitalPoint> points = reader.read(query);
    VitalsHistory history =
        new VitalsHistory(query.patientId(), query.resolution(), query.from(), query.to(), points);
    cache.put(query, history);
    return history;
  }
}
