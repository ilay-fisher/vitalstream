package io.vitalstream.ingest.adapters;

import static org.assertj.core.api.Assertions.assertThat;

import io.vitalstream.ingest.SharedContainers;
import io.vitalstream.ingest.adapters.outbound.RedisIdempotencyStore;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

@Tag("integration")
class RedisIdempotencyStoreIT {

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

  @Test
  void firstClaimWinsAndSecondIsDuplicate() {
    var store = new RedisIdempotencyStore(redis, Duration.ofHours(24));
    UUID id = UUID.randomUUID();

    assertThat(store.claim(id)).isTrue();
    assertThat(store.claim(id)).isFalse();
  }

  @Test
  void keyUsesContractNameAndTtl() {
    var store = new RedisIdempotencyStore(redis, Duration.ofHours(24));
    UUID id = UUID.randomUUID();

    store.claim(id);

    Long ttl = redis.getExpire("idem:" + id);
    assertThat(ttl)
        .isBetween(Duration.ofHours(24).toSeconds() - 5, Duration.ofHours(24).toSeconds());
  }

  @Test
  void releaseLetsTheNextCallerClaimAgain() {
    var store = new RedisIdempotencyStore(redis, Duration.ofHours(24));
    UUID id = UUID.randomUUID();
    store.claim(id);

    store.release(id);

    assertThat(store.claim(id)).isTrue();
  }

  @Test
  void exactlyOneOfManyConcurrentClaimsWins() throws Exception {
    var store = new RedisIdempotencyStore(redis, Duration.ofHours(24));
    UUID id = UUID.randomUUID();
    int threads = 16;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    CountDownLatch start = new CountDownLatch(1);
    AtomicInteger winners = new AtomicInteger();
    List<Future<?>> futures = new ArrayList<>();
    for (int i = 0; i < threads; i++) {
      futures.add(
          pool.submit(() -> {
            start.await();
            if (store.claim(id)) {
              winners.incrementAndGet();
            }
            return null;
          }));
    }

    start.countDown();
    for (Future<?> f : futures) {
      f.get();
    }
    pool.shutdown();

    assertThat(winners.get()).isEqualTo(1);
  }
}
