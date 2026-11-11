package io.vitalstream.clinical.infrastructure.redis;

import io.vitalstream.clinical.application.VitalsCache;
import io.vitalstream.clinical.application.VitalsHistory;
import io.vitalstream.clinical.application.VitalsQuery;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Cache-aside store for history responses, key {@code cache:vitals:{patientId}:{hash(query)}}.
 * Cache trouble never fails a request: Cassandra stays the source of truth.
 */
@Component
public class RedisVitalsCache implements VitalsCache {

  static final Duration TTL = Duration.ofSeconds(10);
  private static final Logger log = LoggerFactory.getLogger(RedisVitalsCache.class);

  private final StringRedisTemplate redis;
  private final JsonMapper mapper;

  public RedisVitalsCache(StringRedisTemplate redis, JsonMapper mapper) {
    this.redis = redis;
    this.mapper = mapper;
  }

  @Override
  public Optional<VitalsHistory> get(VitalsQuery query) {
    try {
      String json = redis.opsForValue().get(key(query));
      return json == null
          ? Optional.empty()
          : Optional.of(mapper.readValue(json, VitalsHistory.class));
    } catch (RuntimeException e) {
      log.warn("Vitals cache read failed: {}", e.toString());
      return Optional.empty();
    }
  }

  @Override
  public void put(VitalsQuery query, VitalsHistory history) {
    try {
      redis.opsForValue().set(key(query), mapper.writeValueAsString(history), TTL);
    } catch (RuntimeException e) {
      log.warn("Vitals cache write failed: {}", e.toString());
    }
  }

  static String key(VitalsQuery q) {
    String canonical = q.metric() + "|" + q.from() + "|" + q.to() + "|" + q.resolution();
    return "cache:vitals:" + q.patientId() + ":" + sha256Prefix(canonical);
  }

  private static String sha256Prefix(String value) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest, 0, 8);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
