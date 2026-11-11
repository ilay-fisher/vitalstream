package io.vitalstream.clinical.infrastructure.redis;

import io.vitalstream.clinical.application.LatestVital;
import io.vitalstream.clinical.application.LatestVitalsStore;
import io.vitalstream.clinical.application.PatientRef;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Reads the {@code patient:{id}:latest} hash maintained by the stream processor. */
@Component
public class RedisLatestVitalsStore implements LatestVitalsStore {

  private static final Logger log = LoggerFactory.getLogger(RedisLatestVitalsStore.class);

  private final StringRedisTemplate redis;
  private final JsonMapper mapper;

  public RedisLatestVitalsStore(StringRedisTemplate redis, JsonMapper mapper) {
    this.redis = redis;
    this.mapper = mapper;
  }

  @Override
  public Map<String, LatestVital> latest(String patientId) {
    Map<Object, Object> raw = redis.opsForHash().entries("patient:" + patientId + ":latest");
    Map<String, LatestVital> result = new HashMap<>();
    raw.forEach((metric, json) -> {
      try {
        result.put(metric.toString(), mapper.readValue(json.toString(), LatestVital.class));
      } catch (RuntimeException e) {
        log.warn(
            "Skipping unreadable latest vital {} for {}: {}",
            metric,
            PatientRef.of(patientId),
            e.toString());
      }
    });
    return result;
  }
}
