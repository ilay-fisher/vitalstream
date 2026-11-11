package io.vitalstream.clinical.domain;

/** A monitored patient as stored in {@code clinical.patients}. */
public record Patient(String id, String name, String bed, String ward) {}
