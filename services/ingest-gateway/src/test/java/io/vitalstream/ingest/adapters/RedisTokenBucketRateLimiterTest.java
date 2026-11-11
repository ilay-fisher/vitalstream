package io.vitalstream.ingest.adapters;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.vitalstream.ingest.adapters.outbound.RedisTokenBucketRateLimiter;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

class RedisTokenBucketRateLimiterTest {

  private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
  private final RedisTokenBucketRateLimiter limiter =
      new RedisTokenBucketRateLimiter(redis, 50, 100);

  @SuppressWarnings({"unchecked", "rawtypes"})
  private void scriptReturns(List<Long> result) {
    when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(result);
  }

  @Test
  void failsOpenWhenRedisIsDown() {
    scriptThrows();

    assertThat(limiter.tryAcquire("DEV-ICU-0001").allowed()).isTrue();
  }

  @Test
  void roundsRetryAfterUpToWholeSeconds() {
    scriptReturns(List.of(0L, 1500L));

    var decision = limiter.tryAcquire("DEV-ICU-0001");

    assertThat(decision.allowed()).isFalse();
    assertThat(decision.retryAfterSeconds()).isEqualTo(2);
  }

  @Test
  void subSecondWaitStillAdvertisesOneSecond() {
    scriptReturns(List.of(0L, 20L));

    assertThat(limiter.tryAcquire("DEV-ICU-0001").retryAfterSeconds()).isEqualTo(1);
  }

  @Test
  void emptyScriptResultIsTreatedAsPermit() {
    scriptReturns(List.of());

    assertThat(limiter.tryAcquire("DEV-ICU-0001").allowed()).isTrue();
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private void scriptThrows() {
    when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
        .thenThrow(new RedisConnectionFailureException("down"));
  }
}
