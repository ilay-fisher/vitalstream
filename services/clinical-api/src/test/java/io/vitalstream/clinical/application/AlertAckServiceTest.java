package io.vitalstream.clinical.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.vitalstream.clinical.domain.Alert;
import io.vitalstream.clinical.domain.AlertNotFoundException;
import io.vitalstream.clinical.domain.AlertStatus;
import io.vitalstream.clinical.domain.ConcurrentAlertUpdateException;
import io.vitalstream.clinical.domain.InvalidStatusTransitionException;
import io.vitalstream.clinical.domain.OutboxEventType;
import io.vitalstream.clinical.domain.Severity;
import io.vitalstream.clinical.domain.TestAlerts;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

class AlertAckServiceTest {

  private final AlertRepository alerts = mock(AlertRepository.class);
  private final OutboxRepository outbox = mock(OutboxRepository.class);
  private final LiveFeedPublisher liveFeed = mock(LiveFeedPublisher.class);
  private AlertAckService service;

  @BeforeEach
  void setUp() {
    service =
        new AlertAckService(
            alerts,
            outbox,
            liveFeed,
            new DirectUnitOfWork(),
            new OutboxPayloads(JsonMapper.builder().build()),
            Clock.fixed(Instant.parse("2026-11-11T20:20:00Z"), ZoneOffset.UTC));
  }

  @Test
  void acknowledgesPersistsWithExpectedVersionIndexesAndPublishes() {
    Alert open = TestAlerts.alert(Severity.HIGH).withVersion(4);
    when(alerts.findById(open.id())).thenReturn(Optional.of(open));
    when(alerts.updateAcknowledgement(any(), org.mockito.ArgumentMatchers.eq(4L))).thenReturn(true);

    AlertView view = service.acknowledge(open.id(), "dr.cohen", "seen");

    assertThat(view.status()).isEqualTo(AlertStatus.ACKNOWLEDGED);
    assertThat(view.acknowledgedBy()).isEqualTo("dr.cohen");
    assertThat(view.version()).isEqualTo(5);
    ArgumentCaptor<OutboxEntry> row = ArgumentCaptor.forClass(OutboxEntry.class);
    verify(outbox).add(row.capture());
    assertThat(row.getValue().eventType()).isEqualTo(OutboxEventType.ALERT_INDEX);
    verify(liveFeed).publish(view);
  }

  @Test
  void unknownAlertIsNotFound() {
    UUID id = UUID.randomUUID();
    when(alerts.findById(id)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.acknowledge(id, "dr", null))
        .isInstanceOf(AlertNotFoundException.class);
  }

  @Test
  void lostOptimisticLockRaisesConflictAndWritesNothingElse() {
    Alert open = TestAlerts.alert(Severity.HIGH);
    when(alerts.findById(open.id())).thenReturn(Optional.of(open));
    when(alerts.updateAcknowledgement(any(), org.mockito.ArgumentMatchers.anyLong()))
        .thenReturn(false);

    assertThatThrownBy(() -> service.acknowledge(open.id(), "dr", null))
        .isInstanceOf(ConcurrentAlertUpdateException.class);
    verify(outbox, never()).add(any());
    verify(liveFeed, never()).publish(any());
  }

  @Test
  void alreadyAcknowledgedAlertIsRejected() {
    Alert acked = TestAlerts.alert(Severity.HIGH).acknowledge("a", null, Instant.now());
    when(alerts.findById(acked.id())).thenReturn(Optional.of(acked));

    assertThatThrownBy(() -> service.acknowledge(acked.id(), "b", null))
        .isInstanceOf(InvalidStatusTransitionException.class);
  }

  @Test
  void liveFeedFailureDoesNotFailAcknowledgement() {
    Alert open = TestAlerts.alert(Severity.HIGH);
    when(alerts.findById(open.id())).thenReturn(Optional.of(open));
    when(alerts.updateAcknowledgement(any(), org.mockito.ArgumentMatchers.anyLong()))
        .thenReturn(true);
    doThrow(new IllegalStateException("redis down")).when(liveFeed).publish(any());

    assertThat(service.acknowledge(open.id(), "dr", null).status())
        .isEqualTo(AlertStatus.ACKNOWLEDGED);
  }
}
