package io.vitalstream.clinical.application;

import java.util.List;

/** Port: time-series reads (Cassandra). */
public interface VitalsHistoryReader {

  List<VitalPoint> read(VitalsQuery query);
}
