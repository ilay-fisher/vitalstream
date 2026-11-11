package io.vitalstream.clinical.application;

import io.vitalstream.clinical.domain.OutboxEventType;

/** Parsed outbox row handed to a sink: the alert snapshot plus the correlation id to propagate. */
public record OutboxMessage(OutboxEventType type, String correlationId, AlertView alert) {}
