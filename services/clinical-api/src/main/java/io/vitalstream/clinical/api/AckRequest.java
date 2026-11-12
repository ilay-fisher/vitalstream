package io.vitalstream.clinical.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /api/v1/alerts/{id}/ack}. The actor is the authenticated user, so a legacy
 * {@code by} field is deliberately dropped instead of trusted.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AckRequest(@Size(max = 1000) String note) {}
