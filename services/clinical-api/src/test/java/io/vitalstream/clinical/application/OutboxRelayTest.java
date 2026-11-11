package io.vitalstream.clinical.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.vitalstream.clinical.domain.Alert;
import io.vitalstream.clinical.domain.OutboxEventType;
import io.vitalstream.clinical.domain.Severity;
import io.vitalstream.clinical.domain.TestAlerts;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import tools.jackson.databind.json.JsonMapper;

class OutboxRelayTest {

  private static final Instant NOW = Instant.parse("2026-11-11T20:20:00Z");

  private final OutboxRepository outbox = mock(OutboxRepository.class);
  private final OutboxSink notifySink = mock(OutboxSink.class);
  private final OutboxSink indexSink = mock(OutboxSink.class);
  private final OutboxPayloads payloads = new OutboxPayloads(JsonMapper.builder().build());
  private OutboxRelay relay;

  @BeforeEach
  void setUp() {
    when(notifySink.handles()).thenReturn(OutboxEventType.ALERT_NOTIFY);
    when(indexSink.handles()).thenReturn(OutboxEventType.ALERT_INDEX);
    relay =
        new OutboxRelay(
            outbox,
            payloads,
            new DirectUnitOfWork(),
            Clock.fixed(NOW, ZoneOffset.UTC),
            List.of(notifySink, indexSink));
  }

  @Test
  void deliversEachRowToItsSinkAndMarksItPublished() throws Exception {
    OutboxEntry notify = entry(OutboxEventType.ALERT_NOTIFY, "corr-9");
    OutboxEntry index = entry(OutboxEventType.ALERT_INDEX, "corr-9");
    when(outbox.lockUnpublished(10)).thenReturn(List.of(notify, index));

    int delivered = relay.relayBatch(10);

    assertThat(delivered).isEqualTo(2);
    verify(notifySink).deliver(any(OutboxMessage.class));
    verify(indexSink).deliver(any(OutboxMessage.class));
    verify(outbox).markPublished(notify.id(), NOW);
    verify(outbox).markPublished(index.id(), NOW);
  }

  @Test
  void failedRowStaysUnpublishedAndDoesNotBlockTheRestOfTheBatch() throws Exception {
    OutboxEntry failing = entry(OutboxEventType.ALERT_NOTIFY, "c");
    OutboxEntry healthy = entry(OutboxEventType.ALERT_INDEX, "c");
    when(outbox.lockUnpublished(10)).thenReturn(List.of(failing, healthy));
    doThrow(new IllegalStateException("broker down")).when(notifySink).deliver(any());

    int delivered = relay.relayBatch(10);

    assertThat(delivered).isEqualTo(1);
    verify(outbox, never()).markPublished(failing.id(), NOW);
    verify(outbox).markPublished(healthy.id(), NOW);
  }

  @Test
  void correlationIdIsRestoredWhileDeliveringAndClearedAfterwards() throws Exception {
    List<String> seen = new ArrayList<>();
    org.mockito.Mockito.doAnswer(
            inv -> {
              seen.add(MDC.get(Correlation.MDC_KEY));
              return null;
            })
        .when(notifySink)
        .deliver(any());
    when(outbox.lockUnpublished(1))
        .thenReturn(List.of(entry(OutboxEventType.ALERT_NOTIFY, "corr-7")));

    relay.relayBatch(1);

    assertThat(seen).containsExactly("corr-7");
    assertThat(MDC.get(Correlation.MDC_KEY)).isNull();
  }

  @Test
  void rowWithoutRegisteredSinkIsLeftUnpublished() {
    OutboxEntry escalate = entry(OutboxEventType.ALERT_ESCALATE, "c");
    when(outbox.lockUnpublished(5)).thenReturn(List.of(escalate));

    assertThat(relay.relayBatch(5)).isZero();
    verify(outbox, never()).markPublished(any(), any());
  }

  @Test
  void unreadablePayloadIsLeftUnpublished() {
    OutboxEntry broken =
        new OutboxEntry(
            UUID.randomUUID(), UUID.randomUUID(), OutboxEventType.ALERT_INDEX, "{nope", NOW);
    when(outbox.lockUnpublished(5)).thenReturn(List.of(broken));

    assertThat(relay.relayBatch(5)).isZero();
  }

  private OutboxEntry entry(OutboxEventType type, String correlationId) {
    Alert alert = TestAlerts.alert(Severity.CRITICAL);
    return new OutboxEntry(
        UUID.randomUUID(),
        alert.id(),
        type,
        payloads.write(correlationId, AlertView.from(alert)),
        NOW);
  }
}
