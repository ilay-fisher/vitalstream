package io.vitalstream.clinical.application;

import io.vitalstream.clinical.domain.OutboxEventType;

/** Port: one downstream destination of the outbox relay. */
public interface OutboxSink {

  OutboxEventType handles();

  /** Delivers the message; any exception leaves the outbox row unpublished for a later retry. */
  void deliver(OutboxMessage message) throws Exception;
}
