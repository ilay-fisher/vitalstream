package io.vitalstream.clinical.infrastructure.config;

import io.vitalstream.clinical.infrastructure.redis.RedisLiveFeedPublisher;
import io.vitalstream.clinical.infrastructure.redis.RedisLiveFeedSubscriber;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/** Subscribes this replica to the live alert channel. */
@Configuration
public class RedisConfig {

  @Bean
  RedisMessageListenerContainer liveFeedListenerContainer(
      RedisConnectionFactory connectionFactory, RedisLiveFeedSubscriber subscriber) {
    RedisMessageListenerContainer container = new RedisMessageListenerContainer();
    container.setConnectionFactory(connectionFactory);
    container.addMessageListener(subscriber, new ChannelTopic(RedisLiveFeedPublisher.CHANNEL));
    return container;
  }
}
