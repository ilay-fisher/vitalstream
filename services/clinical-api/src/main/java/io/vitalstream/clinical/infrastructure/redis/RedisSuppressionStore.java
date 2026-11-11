package io.vitalstream.clinical.infrastructure.redis;

import io.vitalstream.clinical.application.SuppressionStore;
import java.time.Duration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Suppression windows in Redis so all replicas agree. {@code SET NX PX} makes claiming atomic; the
 * value stores the claiming alert id so a redelivered alert recognises its own claim.
 */
@Component
public class RedisSuppressionStore implements SuppressionStore {

  private final StringRedisTemplate redis;

  public RedisSuppressionStore(StringRedisTemplate redis) {
    this.redis = redis;
  }

  @Override
  public boolean tryAcquire(String key, String owner, Duration window) {
    if (Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key, owner, window))) {
      return true;
    }
    return owner.equals(redis.opsForValue().get(key));
  }
}
