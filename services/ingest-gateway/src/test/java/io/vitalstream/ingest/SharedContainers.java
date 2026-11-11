package io.vitalstream.ingest;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Process-wide singleton containers shared by all integration tests, so Redis and Kafka start once
 * per test JVM instead of once per class. Ryuk removes them when the JVM exits.
 */
public final class SharedContainers {

  public static final GenericContainer<?> REDIS =
      new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379);

  public static final KafkaContainer KAFKA =
      new KafkaContainer(DockerImageName.parse("apache/kafka:3.8.0"));

  private SharedContainers() {}

  public static synchronized void startRedis() {
    if (!REDIS.isRunning()) {
      REDIS.start();
    }
  }

  public static synchronized void startKafka() {
    if (!KAFKA.isRunning()) {
      KAFKA.start();
    }
  }

  public static String redisUrl() {
    startRedis();
    return "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379);
  }
}
