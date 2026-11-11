package io.vitalstream.ingest.adapters.outbound;

import io.vitalstream.ingest.domain.port.RateLimitDecision;
import io.vitalstream.ingest.domain.port.RateLimiter;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * Token-bucket rate limiter whose whole read-refill-consume cycle runs as one Lua script in Redis.
 * Doing it in a script (rather than GET then SET from Java) is what makes it correct when several
 * gateway instances share the bucket: no interleaving can double-spend a token.
 *
 * <p>If Redis is unreachable the limiter fails open. Dropping clinical data because the protective
 * layer is down would be worse than briefly not throttling.
 */
public class RedisTokenBucketRateLimiter implements RateLimiter {

  private static final Logger log = LoggerFactory.getLogger(RedisTokenBucketRateLimiter.class);

  static final String KEY_PREFIX = "rl:";

  /** CONTRACTS section 5: bucket keys expire after 10 seconds of inactivity. */
  static final long KEY_TTL_SECONDS = 10;

  @SuppressWarnings("rawtypes")
  private static final RedisScript<List> SCRIPT = loadScript();

  private final StringRedisTemplate redis;
  private final double ratePerSecond;
  private final int burst;

  public RedisTokenBucketRateLimiter(StringRedisTemplate redis, double ratePerSecond, int burst) {
    this.redis = redis;
    this.ratePerSecond = ratePerSecond;
    this.burst = burst;
  }

  @Override
  public RateLimitDecision tryAcquire(String deviceId) {
    try {
      List<?> result =
          redis.execute(
              SCRIPT,
              List.of(KEY_PREFIX + deviceId),
              String.valueOf(ratePerSecond),
              String.valueOf(burst),
              "1",
              String.valueOf(KEY_TTL_SECONDS));
      if (result == null || result.size() < 2) {
        return RateLimitDecision.permit();
      }
      boolean allowed = ((Number) result.get(0)).longValue() == 1L;
      long retryAfterMs = ((Number) result.get(1)).longValue();
      return allowed
          ? RateLimitDecision.permit()
          : RateLimitDecision.deny(ceilSeconds(retryAfterMs));
    } catch (RuntimeException e) {
      log.warn("rate limiter unavailable, failing open deviceId={}", deviceId, e);
      return RateLimitDecision.permit();
    }
  }

  private static long ceilSeconds(long millis) {
    return (millis + 999) / 1000;
  }

  @SuppressWarnings("rawtypes")
  private static RedisScript<List> loadScript() {
    DefaultRedisScript<List> script = new DefaultRedisScript<>();
    script.setLocation(new ClassPathResource("scripts/token_bucket.lua"));
    script.setResultType(List.class);
    return script;
  }
}
