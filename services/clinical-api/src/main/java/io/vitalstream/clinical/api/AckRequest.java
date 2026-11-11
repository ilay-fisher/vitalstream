package io.vitalstream.clinical.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Body of {@code POST /api/v1/alerts/{id}/ack}. */
public record AckRequest(@NotBlank @Size(max = 100) String by, @Size(max = 1000) String note) {}
