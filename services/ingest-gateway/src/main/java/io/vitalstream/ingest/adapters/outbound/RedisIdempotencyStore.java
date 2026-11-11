package io.vitalstream.ingest.adapters.outbound;

import io.vitalstream.ingest.domain.port.IdempotencyStore;
import java.time.Duration;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Idempotency backed by Redis {@code SET key value NX EX ttl}. NX makes the check-and-claim one
 * atomic round trip, so two concurrent requests carrying the same event id can never both win.
 */
public class RedisIdempotencyStore implements IdempotencyStore {

  static final String KEY_PREFIX = "idem:";

  private final StringRedisTemplate redis;
  private final Duration ttl;

  public RedisIdempotencyStore(StringRedisTemplate redis, Duration ttl) {
    this.redis = redis;
    this.ttl = ttl;
  }

  @Override
  public boolean claim(UUID eventId) {
    return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key(eventId), "1", ttl));
  }

  @Override
  public void release(UUID eventId) {
    redis.delete(key(eventId));
  }

  private static String key(UUID eventId) {
    return KEY_PREFIX + eventId;
  }
}
