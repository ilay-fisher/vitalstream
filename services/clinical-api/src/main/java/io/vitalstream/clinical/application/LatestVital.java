package io.vitalstream.clinical.application;

import java.time.Instant;

/** Most recent reading of one metric, as cached by the stream processor. */
public record LatestVital(Double value, String unit, Instant observedAt) {}
