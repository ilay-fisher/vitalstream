package io.vitalstream.clinical.application;

import java.util.Map;

/** Patient master data with live vitals and the number of alerts awaiting acknowledgement. */
public record PatientView(
    String id,
    String name,
    String bed,
    String ward,
    Map<String, LatestVital> latestVitals,
    long openAlerts) {}
