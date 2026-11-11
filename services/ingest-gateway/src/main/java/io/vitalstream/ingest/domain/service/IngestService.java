package io.vitalstream.ingest.domain.service;

import io.vitalstream.ingest.domain.model.RawReading;
import io.vitalstream.ingest.domain.model.Rejection;
import io.vitalstream.ingest.domain.model.VitalReading;
import io.vitalstream.ingest.domain.port.EventPublisher;
import io.vitalstream.ingest.domain.port.IdempotencyStore;
import io.vitalstream.ingest.domain.port.IngestMetrics;
import io.vitalstream.ingest.domain.port.RateLimitDecision;
import io.vitalstream.ingest.domain.port.RateLimiter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Use case shared by all inbound formats: rate limit, validate, de-duplicate, publish.
 *
 * <p>The order matters. Rate limiting comes first so a flood cannot cause Redis or Kafka work; the
 * idempotency claim comes right before the publish and is undone if the publish fails, so a client
 * retry is never swallowed as a "duplicate" of something that was never delivered.
 */
public class IngestService {

  private static final Logger log = LoggerFactory.getLogger(IngestService.class);

  private final ReadingValidator validator;
  private final IdempotencyStore idempotency;
  private final RateLimiter rateLimiter;
  private final EventPublisher publisher;
  private final IngestMetrics metrics;

  public IngestService(
      ReadingValidator validator,
      IdempotencyStore idempotency,
      RateLimiter rateLimiter,
      EventPublisher publisher,
      IngestMetrics metrics) {
    this.validator = validator;
    this.idempotency = idempotency;
    this.rateLimiter = rateLimiter;
    this.publisher = publisher;
    this.metrics = metrics;
  }

  /**
   * Processes one inbound request worth of readings.
   *
   * @throws RateLimitExceededException when a device in the request is over its limit
   * @throws PublishFailedException when the broker did not acknowledge every fresh reading
   */
  public IngestResult ingest(List<RawReading> raws) {
    return ingest(raws, List.of());
  }

  /**
   * Same as {@link #ingest(List)} for adapters that already refused some items themselves (for
   * example non-Observation entries of a FHIR Bundle); those are reported and counted alongside
   * validation rejections.
   */
  public IngestResult ingest(List<RawReading> raws, List<Rejection> adapterRejections) {
    enforceRateLimit(raws);

    List<Rejection> rejected = new ArrayList<>(adapterRejections);
    adapterRejections.forEach(r -> metrics.rejected(r.code()));
    List<VitalReading> fresh = new ArrayList<>();
    int duplicates = claimFreshReadings(raws, rejected, fresh);

    awaitPublish(fresh);

    fresh.forEach(r -> metrics.ingested(r.source(), r.metric()));
    log.info(
        "ingest processed accepted={} duplicates={} rejected={}",
        fresh.size(),
        duplicates,
        rejected.size());
    return new IngestResult(fresh.size(), duplicates, List.copyOf(rejected));
  }

  private void enforceRateLimit(List<RawReading> raws) {
    Set<String> devices = new LinkedHashSet<>();
    for (RawReading raw : raws) {
      if (ReadingValidator.isValidDeviceId(raw.deviceId())) {
        devices.add(raw.deviceId());
      }
    }
    for (String device : devices) {
      RateLimitDecision decision = rateLimiter.tryAcquire(device);
      if (!decision.allowed()) {
        log.warn("rate limit exceeded deviceId={}", device);
        throw new RateLimitExceededException(device, decision.retryAfterSeconds());
      }
    }
  }

  private int claimFreshReadings(
      List<RawReading> raws, List<Rejection> rejected, List<VitalReading> fresh) {
    int duplicates = 0;
    try {
      for (RawReading raw : raws) {
        switch (validator.validate(raw)) {
          case ValidationResult.Invalid invalid -> {
            rejected.add(invalid.rejection());
            metrics.rejected(invalid.rejection().code());
            log.warn(
                "reading rejected eventId={} reason={}",
                raw.eventId(),
                invalid.rejection().reason());
          }
          case ValidationResult.Valid valid -> {
            if (idempotency.claim(valid.reading().eventId())) {
              fresh.add(valid.reading());
            } else {
              duplicates++;
              metrics.duplicate();
            }
          }
        }
      }
    } catch (RuntimeException e) {
      fresh.forEach(r -> releaseQuietly(r));
      throw e;
    }
    return duplicates;
  }

  private void awaitPublish(List<VitalReading> fresh) {
    List<CompletableFuture<Void>> futures = new ArrayList<>(fresh.size());
    for (VitalReading reading : fresh) {
      futures.add(publishSafely(reading));
    }
    Throwable firstFailure = null;
    int failures = 0;
    for (int i = 0; i < futures.size(); i++) {
      try {
        futures.get(i).join();
      } catch (CompletionException | java.util.concurrent.CancellationException e) {
        failures++;
        firstFailure = firstFailure == null ? e : firstFailure;
        releaseQuietly(fresh.get(i));
      }
    }
    if (failures > 0) {
      throw new PublishFailedException(
          failures + " of " + fresh.size() + " readings were not acknowledged by the broker",
          firstFailure);
    }
  }

  private CompletableFuture<Void> publishSafely(VitalReading reading) {
    try {
      return publisher.publish(reading);
    } catch (RuntimeException e) {
      return CompletableFuture.failedFuture(e);
    }
  }

  private void releaseQuietly(VitalReading reading) {
    try {
      idempotency.release(reading.eventId());
    } catch (RuntimeException e) {
      log.error("could not release idempotency key eventId={}", reading.eventId(), e);
    }
  }
}
