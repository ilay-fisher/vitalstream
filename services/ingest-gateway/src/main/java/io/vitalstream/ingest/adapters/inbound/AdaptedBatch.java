package io.vitalstream.ingest.adapters.inbound;

import io.vitalstream.ingest.domain.model.RawReading;
import io.vitalstream.ingest.domain.model.Rejection;
import java.util.List;

/**
 * What an inbound adapter produced: readings still to be validated, plus items the adapter itself
 * had to refuse (for example a non-Observation resource inside a FHIR Bundle).
 */
public record AdaptedBatch(List<RawReading> readings, List<Rejection> rejections) {}
