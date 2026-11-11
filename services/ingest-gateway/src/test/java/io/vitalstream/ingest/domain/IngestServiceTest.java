package io.vitalstream.ingest.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.vitalstream.ingest.domain.model.RawReading;
import io.vitalstream.ingest.domain.model.RejectionReason;
import io.vitalstream.ingest.domain.model.Source;
import io.vitalstream.ingest.domain.model.VitalReading;
import io.vitalstream.ingest.domain.port.EventPublisher;
import io.vitalstream.ingest.domain.port.IdempotencyStore;
import io.vitalstream.ingest.domain.port.IngestMetrics;
import io.vitalstream.ingest.domain.port.RateLimitDecision;
import io.vitalstream.ingest.domain.port.RateLimiter;
import io.vitalstream.ingest.domain.service.IngestResult;
import io.vitalstream.ingest.domain.service.IngestService;
import io.vitalstream.ingest.domain.service.PublishFailedException;
import io.vitalstream.ingest.domain.service.RateLimitExceededException;
import io.vitalstream.ingest.domain.service.ReadingValidator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class IngestServiceTest {

  private static final Instant NOW = Instant.parse("2026-11-11T20:15:31Z");
  private static final UUID ID_1 = UUID.fromString("6f1c2a1e-8d0b-4c11-9a77-3f0c2b9e1d10");
  private static final UUID ID_2 = UUID.fromString("7f1c2a1e-8d0b-4c11-9a77-3f0c2b9e1d10");

  private final IdempotencyStore idempotency = mock(IdempotencyStore.class);
  private final RateLimiter rateLimiter = mock(RateLimiter.class);
  private final EventPublisher publisher = mock(EventPublisher.class);
  private final IngestMetrics metrics = mock(IngestMetrics.class);
  private IngestService service;

  @BeforeEach
  void setUp() {
    service =
        new IngestService(
            new ReadingValidator(Clock.fixed(NOW, ZoneOffset.UTC)),
            idempotency,
            rateLimiter,
            publisher,
            metrics);
    when(rateLimiter.tryAcquire(any())).thenReturn(RateLimitDecision.permit());
    when(publisher.publish(any())).thenReturn(CompletableFuture.completedFuture(null));
  }

  private static RawReading raw(UUID id, double value) {
    return new RawReading(
        id.toString(),
        "P-0001",
        "DEV-ICU-0001",
        "HEART_RATE",
        null,
        null,
        value,
        "2026-11-11T20:15:30Z",
        Source.DEVICE_BATCH);
  }

  @Test
  void acceptsFreshReadingsAfterPublishAck() {
    when(idempotency.claim(any())).thenReturn(true);

    IngestResult result = service.ingest(List.of(raw(ID_1, 80), raw(ID_2, 90)));

    assertThat(result.accepted()).isEqualTo(2);
    assertThat(result.duplicates()).isZero();
    assertThat(result.rejected()).isEmpty();
    verify(metrics, org.mockito.Mockito.times(2)).ingested(Source.DEVICE_BATCH);
  }

  @Test
  void duplicatesAreCountedAndNotPublished() {
    when(idempotency.claim(ID_1)).thenReturn(false);
    when(idempotency.claim(ID_2)).thenReturn(true);

    IngestResult result = service.ingest(List.of(raw(ID_1, 80), raw(ID_2, 90)));

    assertThat(result.accepted()).isEqualTo(1);
    assertThat(result.duplicates()).isEqualTo(1);
    verify(metrics).duplicate();
  }

  @Test
  void invalidReadingsAreRejectedWithoutTouchingRedisOrKafka() {
    IngestResult result = service.ingest(List.of(raw(ID_1, 999)));

    assertThat(result.accepted()).isZero();
    assertThat(result.rejected()).hasSize(1);
    assertThat(result.rejected().get(0).code()).isEqualTo(RejectionReason.OUT_OF_RANGE);
    verify(metrics).rejected(RejectionReason.OUT_OF_RANGE);
    verifyNoInteractions(idempotency, publisher);
  }

  @Test
  void rateLimitedDeviceFailsWholeRequestBeforeAnySideEffect() {
    when(rateLimiter.tryAcquire("DEV-ICU-0001")).thenReturn(RateLimitDecision.deny(3));

    assertThatThrownBy(() -> service.ingest(List.of(raw(ID_1, 80))))
        .isInstanceOfSatisfying(
            RateLimitExceededException.class,
            e -> {
              assertThat(e.retryAfterSeconds()).isEqualTo(3);
              assertThat(e.deviceId()).isEqualTo("DEV-ICU-0001");
            });
    verifyNoInteractions(idempotency, publisher);
  }

  @Test
  void rateLimitIsConsumedOncePerDevicePerRequest() {
    when(idempotency.claim(any())).thenReturn(true);

    service.ingest(List.of(raw(ID_1, 80), raw(ID_2, 90)));

    verify(rateLimiter).tryAcquire("DEV-ICU-0001");
  }

  @Test
  void deviceIdsThatCouldNotBeValidDoNotCreateLimiterKeys() {
    RawReading bad =
        new RawReading(
            ID_1.toString(),
            "P-0001",
            "garbage",
            "HEART_RATE",
            null,
            null,
            80.0,
            "2026-11-11T20:15:30Z",
            Source.DEVICE_BATCH);

    service.ingest(List.of(bad));

    verifyNoInteractions(rateLimiter);
  }

  @Test
  void failedPublishReleasesIdempotencyKeyAndFailsRequest() {
    when(idempotency.claim(any())).thenReturn(true);
    when(publisher.publish(any()))
        .thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker down")));

    assertThatThrownBy(() -> service.ingest(List.of(raw(ID_1, 80))))
        .isInstanceOf(PublishFailedException.class);

    verify(idempotency).release(ID_1);
    verify(metrics, never()).ingested(any());
  }

  @Test
  void onlyFailedEventsAreReleasedWhenPublishPartiallyFails() {
    when(idempotency.claim(any())).thenReturn(true);
    when(publisher.publish(any(VitalReading.class)))
        .thenAnswer(inv -> {
          VitalReading r = inv.getArgument(0);
          return r.eventId().equals(ID_1)
              ? CompletableFuture.failedFuture(new RuntimeException("boom"))
              : CompletableFuture.completedFuture(null);
        });

    assertThatThrownBy(() -> service.ingest(List.of(raw(ID_1, 80), raw(ID_2, 90))))
        .isInstanceOf(PublishFailedException.class);

    verify(idempotency).release(ID_1);
    verify(idempotency, never()).release(ID_2);
  }

  @Test
  void redisFailureWhileClaimingReleasesAlreadyClaimedKeys() {
    when(idempotency.claim(ID_1)).thenReturn(true);
    when(idempotency.claim(ID_2)).thenThrow(new IllegalStateException("redis down"));

    assertThatThrownBy(() -> service.ingest(List.of(raw(ID_1, 80), raw(ID_2, 90))))
        .isInstanceOf(IllegalStateException.class);

    verify(idempotency).release(ID_1);
    verifyNoInteractions(publisher);
  }

  @Test
  void releaseFailureDoesNotMaskPublishFailure() {
    when(idempotency.claim(any())).thenReturn(true);
    when(publisher.publish(any()))
        .thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker down")));
    org.mockito.Mockito.doThrow(new IllegalStateException("redis down"))
        .when(idempotency)
        .release(any());

    assertThatThrownBy(() -> service.ingest(List.of(raw(ID_1, 80))))
        .isInstanceOf(PublishFailedException.class);
  }
}
