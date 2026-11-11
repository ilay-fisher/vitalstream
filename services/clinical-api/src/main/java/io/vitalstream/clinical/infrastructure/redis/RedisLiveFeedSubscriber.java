package io.vitalstream.clinical.infrastructure.redis;

import io.vitalstream.clinical.application.AlertBroadcaster;
import io.vitalstream.clinical.application.AlertView;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Receives {@code alerts.live} messages on every replica and hands them to local SSE clients. */
@Component
public class RedisLiveFeedSubscriber implements MessageListener {

  private static final Logger log = LoggerFactory.getLogger(RedisLiveFeedSubscriber.class);

  private final AlertBroadcaster broadcaster;
  private final JsonMapper mapper;

  public RedisLiveFeedSubscriber(AlertBroadcaster broadcaster, JsonMapper mapper) {
    this.broadcaster = broadcaster;
    this.mapper = mapper;
  }

  @Override
  public void onMessage(Message message, byte[] pattern) {
    try {
      AlertView alert =
          mapper.readValue(new String(message.getBody(), StandardCharsets.UTF_8), AlertView.class);
      broadcaster.broadcast(alert);
    } catch (RuntimeException e) {
      log.warn("Ignoring malformed live feed message: {}", e.toString());
    }
  }
}
