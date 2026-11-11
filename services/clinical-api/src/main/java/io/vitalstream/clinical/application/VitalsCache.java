package io.vitalstream.clinical.application;

import java.util.Optional;

/** Port: short-lived cache of history responses (Redis, 10 s). */
public interface VitalsCache {

  Optional<VitalsHistory> get(VitalsQuery query);

  void put(VitalsQuery query, VitalsHistory history);
}
