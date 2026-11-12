package io.vitalstream.clinical.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import tools.jackson.databind.json.JsonMapper;

class AlertAckServiceTest {

  private final AlertRepository alerts = mock(AlertRepository.class);
  private final OutboxRepository outbox = mock(OutboxRepository.class);
  private final LiveFeedPublisher liveFeed = mock(LiveFeedPublisher.class);
  private static final AckActor COHEN = new AckActor("dr.cohen", "Dr. Cohen");
  private static final AckActor DR = new AckActor("dr", "dr");
  private AlertAckService service;
  private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

  @BeforeEach
  void setUp() {
    logs.start();
    ((Logger) LoggerFactory.getLogger(AlertAckService.class)).addAppender(logs);
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

    AlertView view = service.acknowledge(open.id(), COHEN, "seen");

    assertThat(view.status()).isEqualTo(AlertStatus.ACKNOWLEDGED);
    assertThat(view.acknowledgedBy()).isEqualTo("Dr. Cohen");
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

    assertThatThrownBy(() -> service.acknowledge(id, DR, null))
        .isInstanceOf(AlertNotFoundException.class);
  }

  @Test
  void lostOptimisticLockRaisesConflictAndWritesNothingElse() {
    Alert open = TestAlerts.alert(Severity.HIGH);
    when(alerts.findById(open.id())).thenReturn(Optional.of(open));
    when(alerts.updateAcknowledgement(any(), org.mockito.ArgumentMatchers.anyLong()))
        .thenReturn(false);

    assertThatThrownBy(() -> service.acknowledge(open.id(), DR, null))
        .isInstanceOf(ConcurrentAlertUpdateException.class);
    verify(outbox, never()).add(any());
    verify(liveFeed, never()).publish(any());
  }

  @Test
  void alreadyAcknowledgedAlertIsRejected() {
    Alert acked = TestAlerts.alert(Severity.HIGH).acknowledge("a", null, Instant.now());
    when(alerts.findById(acked.id())).thenReturn(Optional.of(acked));

    assertThatThrownBy(() -> service.acknowledge(acked.id(), DR, null))
        .isInstanceOf(InvalidStatusTransitionException.class);
  }

  @Test
  void liveFeedFailureDoesNotFailAcknowledgement() {
    Alert open = TestAlerts.alert(Severity.HIGH);
    when(alerts.findById(open.id())).thenReturn(Optional.of(open));
    when(alerts.updateAcknowledgement(any(), org.mockito.ArgumentMatchers.anyLong()))
        .thenReturn(true);
    doThrow(new IllegalStateException("redis down")).when(liveFeed).publish(any());

    assertThat(service.acknowledge(open.id(), DR, null).status())
        .isEqualTo(AlertStatus.ACKNOWLEDGED);
  }

  @AfterEach
  void detachAppender() {
    ((Logger) LoggerFactory.getLogger(AlertAckService.class)).detachAppender(logs);
    MDC.clear();
  }

  @Test
  void writesAnInfoAuditLineWithAlertIdActorSubAndCorrelationId() {
    Alert open = TestAlerts.alert(Severity.HIGH);
    when(alerts.findById(open.id())).thenReturn(Optional.of(open));
    when(alerts.updateAcknowledgement(any(), org.mockito.ArgumentMatchers.anyLong()))
        .thenReturn(true);
    MDC.put("correlationId", "corr-audit");

    service.acknowledge(open.id(), COHEN, "seen");

    ILoggingEvent audit =
        logs.list.stream()
            .filter(e -> e.getFormattedMessage().equals("alert acknowledged"))
            .findFirst()
            .orElseThrow();
    assertThat(audit.getLevel()).isEqualTo(Level.INFO);
    assertThat(audit.getKeyValuePairs())
        .extracting(kv -> kv.key + "=" + kv.value)
        .contains("alertId=" + open.id(), "actor=dr.cohen");
    assertThat(audit.getMDCPropertyMap()).containsEntry("correlationId", "corr-audit");
  }

  @Test
  void auditLineNeverCarriesTheFreeTextNote() {
    Alert open = TestAlerts.alert(Severity.HIGH);
    when(alerts.findById(open.id())).thenReturn(Optional.of(open));
    when(alerts.updateAcknowledgement(any(), org.mockito.ArgumentMatchers.anyLong()))
        .thenReturn(true);

    service.acknowledge(open.id(), COHEN, "patient is Dana Levi");

    assertThat(logs.list).noneMatch(e -> e.getFormattedMessage().contains("Dana"));
  }

  @Test
  void noAuditLineWhenTheAcknowledgementFails() {
    Alert open = TestAlerts.alert(Severity.HIGH);
    when(alerts.findById(open.id())).thenReturn(Optional.of(open));
    when(alerts.updateAcknowledgement(any(), org.mockito.ArgumentMatchers.anyLong()))
        .thenReturn(false);

    assertThatThrownBy(() -> service.acknowledge(open.id(), COHEN, null))
        .isInstanceOf(ConcurrentAlertUpdateException.class);

    assertThat(logs.list).noneMatch(e -> e.getFormattedMessage().equals("alert acknowledged"));
  }
}
