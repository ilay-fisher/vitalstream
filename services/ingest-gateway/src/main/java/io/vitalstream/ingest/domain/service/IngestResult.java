package io.vitalstream.ingest.domain.service;

import io.vitalstream.ingest.domain.model.Rejection;
import java.util.List;

/** Per-request tally returned to the caller (CONTRACTS section 4 response shape). */
public record IngestResult(int accepted, int duplicates, List<Rejection> rejected) {}
