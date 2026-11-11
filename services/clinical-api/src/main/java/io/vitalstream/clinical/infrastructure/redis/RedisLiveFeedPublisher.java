package io.vitalstream.clinical.infrastructure.redis;

import io.vitalstream.clinical.application.AlertView;
import io.vitalstream.clinical.application.LiveFeedPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Publishes alert changes to the {@code alerts.live} channel that every replica subscribes to. */
@Component
public class RedisLiveFeedPublisher implements LiveFeedPublisher {

  public static final String CHANNEL = "alerts.live";

  private final StringRedisTemplate redis;
  private final JsonMapper mapper;

  public RedisLiveFeedPublisher(StringRedisTemplate redis, JsonMapper mapper) {
    this.redis = redis;
    this.mapper = mapper;
  }

  @Override
  public void publish(AlertView alert) {
    redis.convertAndSend(CHANNEL, mapper.writeValueAsString(alert));
  }
}
