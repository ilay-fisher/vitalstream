package io.vitalstream.ingest.domain.port;

import io.vitalstream.ingest.domain.model.RejectionReason;
import io.vitalstream.ingest.domain.model.Source;

/** Business counters, kept behind a port so the domain does not depend on Micrometer. */
public interface IngestMetrics {

  void ingested(Source source);

  void rejected(RejectionReason reason);

  void duplicate();
}
