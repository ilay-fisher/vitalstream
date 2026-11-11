package io.vitalstream.ingest.adapters;

import static org.assertj.core.api.Assertions.assertThat;

import io.vitalstream.ingest.SharedContainers;
import io.vitalstream.ingest.adapters.outbound.RedisTokenBucketRateLimiter;
import io.vitalstream.ingest.domain.port.RateLimitDecision;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

@Tag("integration")
class RedisTokenBucketRateLimiterIT {

  private static StringRedisTemplate redis;

  @BeforeAll
  static void connect() {
    SharedContainers.startRedis();
    LettuceConnectionFactory factory =
        new LettuceConnectionFactory(
            new RedisStandaloneConfiguration(
                SharedContainers.REDIS.getHost(), SharedContainers.REDIS.getMappedPort(6379)));
    factory.afterPropertiesSet();
    redis = new StringRedisTemplate(factory);
    redis.afterPropertiesSet();
  }

  private static String freshDevice() {
    return "DEV-T-" + UUID.randomUUID();
  }

  @Test
  void allowsExactlyBurstRequestsThenDenies() {
    var limiter = new RedisTokenBucketRateLimiter(redis, 1, 5);
    String device = freshDevice();

    for (int i = 0; i < 5; i++) {
      assertThat(limiter.tryAcquire(device).allowed()).as("request %d", i).isTrue();
    }
    RateLimitDecision denied = limiter.tryAcquire(device);

    assertThat(denied.allowed()).isFalse();
    assertThat(denied.retryAfterSeconds()).isGreaterThanOrEqualTo(1);
  }

  @Test
  void bucketsAreIndependentPerDevice() {
    var limiter = new RedisTokenBucketRateLimiter(redis, 1, 1);
    String a = freshDevice();
    String b = freshDevice();

    assertThat(limiter.tryAcquire(a).allowed()).isTrue();
    assertThat(limiter.tryAcquire(a).allowed()).isFalse();
    assertThat(limiter.tryAcquire(b).allowed()).isTrue();
  }

  @Test
  void refillsOverTime() throws InterruptedException {
    var limiter = new RedisTokenBucketRateLimiter(redis, 20, 1);
    String device = freshDevice();
    assertThat(limiter.tryAcquire(device).allowed()).isTrue();
    assertThat(limiter.tryAcquire(device).allowed()).isFalse();

    Thread.sleep(120);

    assertThat(limiter.tryAcquire(device).allowed()).isTrue();
  }

  @Test
  void neverRefillsBeyondBurst() throws InterruptedException {
    var limiter = new RedisTokenBucketRateLimiter(redis, 20, 2);
    String device = freshDevice();
    limiter.tryAcquire(device);

    Thread.sleep(300);

    assertThat(limiter.tryAcquire(device).allowed()).isTrue();
    assertThat(limiter.tryAcquire(device).allowed()).isTrue();
    assertThat(limiter.tryAcquire(device).allowed()).isFalse();
  }

  @Test
  void bucketKeyFollowsContractAndExpires() {
    var limiter = new RedisTokenBucketRateLimiter(redis, 50, 100);
    String device = freshDevice();

    limiter.tryAcquire(device);

    assertThat(redis.opsForHash().keys("rl:" + device)).containsExactlyInAnyOrder("tokens", "ts");
    assertThat(redis.getExpire("rl:" + device)).isBetween(1L, 10L);
  }

  @Test
  void retryAfterReflectsTimeUntilNextToken() {
    var limiter = new RedisTokenBucketRateLimiter(redis, 0.25, 1);
    String device = freshDevice();
    limiter.tryAcquire(device);

    RateLimitDecision denied = limiter.tryAcquire(device);

    assertThat(denied.retryAfterSeconds()).isBetween(3L, 4L);
  }
}
