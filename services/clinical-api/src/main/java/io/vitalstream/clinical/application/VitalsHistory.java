package io.vitalstream.clinical.application;

import java.time.Instant;
import java.util.List;

/** Response body of the vitals history endpoint. */
public record VitalsHistory(
    String patientId,
    VitalsQuery.Resolution resolution,
    Instant from,
    Instant to,
    List<VitalPoint> points) {}
