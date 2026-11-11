package io.vitalstream.clinical.application;

import io.vitalstream.clinical.domain.OutboxEventType;
import java.time.Instant;
import java.util.UUID;

/** A row of {@code clinical.outbox}: one pending side effect for one alert. */
public record OutboxEntry(
    UUID id, UUID aggregateId, OutboxEventType eventType, String payload, Instant createdAt) {}
