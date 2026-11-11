package io.vitalstream.clinical.integration;

import com.datastax.oss.driver.api.core.CqlSession;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.localstack.LocalStackContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;

/**
 * Singleton containers shared by every integration test class: starting seven containers per class
 * would dominate the build time. Heaps of Elasticsearch and Cassandra are capped because Docker
 * memory is shared with other workers.
 */
final class Infra {

  static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg16"))
          .withDatabaseName("vitalstream")
          .withUsername("vitalstream")
          .withPassword("vitalstream")
          .withInitScript("it/infra-init.sql");

  static final KafkaContainer KAFKA =
      new KafkaContainer(DockerImageName.parse("apache/kafka:3.9.0"));

  static final GenericContainer<?> REDIS =
      new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

  static final RabbitMQContainer RABBIT =
      new RabbitMQContainer(DockerImageName.parse("rabbitmq:3.13-management-alpine"));

  static final GenericContainer<?> ELASTICSEARCH =
      new GenericContainer<>(
              DockerImageName.parse("docker.elastic.co/elasticsearch/elasticsearch:8.15.3"))
          .withExposedPorts(9200)
          .withEnv("discovery.type", "single-node")
          .withEnv("xpack.security.enabled", "false")
          .withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m")
          .waitingFor(
              Wait.forHttp("/_cluster/health?wait_for_status=yellow&timeout=60s")
                  .forPort(9200)
                  .withStartupTimeout(java.time.Duration.ofMinutes(3)));

  static final GenericContainer<?> CASSANDRA =
      new GenericContainer<>(DockerImageName.parse("cassandra:4.1"))
          .withExposedPorts(9042)
          .withEnv("MAX_HEAP_SIZE", "1G")
          .withEnv("HEAP_NEWSIZE", "256M")
          .withEnv("CASSANDRA_DC", "datacenter1")
          .waitingFor(
              Wait.forLogMessage(".*Starting listening for CQL clients.*\\n", 1)
                  .withStartupTimeout(java.time.Duration.ofMinutes(4)));

  static final LocalStackContainer LOCALSTACK =
      new LocalStackContainer(DockerImageName.parse("localstack/localstack:3.8"))
          .withServices("sqs");

  static {
    List.of(POSTGRES, KAFKA, REDIS, RABBIT, ELASTICSEARCH, CASSANDRA, LOCALSTACK).parallelStream()
        .forEach(GenericContainer::start);
    createTopics();
    declareRabbitTopology();
    createQueues();
    createCassandraSchema();
  }

  private Infra() {}

  static String cassandraContactPoint() {
    return CASSANDRA.getHost() + ":" + CASSANDRA.getMappedPort(9042);
  }

  static String elasticsearchUrl() {
    return "http://" + ELASTICSEARCH.getHost() + ":" + ELASTICSEARCH.getMappedPort(9200);
  }

  static String rabbitUri() {
    return "amqp://guest:guest@" + RABBIT.getHost() + ":" + RABBIT.getAmqpPort();
  }

  static SqsClient sqsClient() {
    return SqsClient.builder()
        .endpointOverride(LOCALSTACK.getEndpoint())
        .region(Region.US_EAST_1)
        .credentialsProvider(
            StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
        .build();
  }

  static CqlSession cassandraSession(String keyspace) {
    var builder =
        CqlSession.builder()
            .addContactPoint(
                new InetSocketAddress(CASSANDRA.getHost(), CASSANDRA.getMappedPort(9042)))
            .withLocalDatacenter("datacenter1");
    return keyspace == null ? builder.build() : builder.withKeyspace(keyspace).build();
  }

  static Channel rabbitChannel() throws Exception {
    ConnectionFactory factory = new ConnectionFactory();
    factory.setUri(rabbitUri());
    Connection connection = factory.newConnection();
    return connection.createChannel();
  }

  private static void createTopics() {
    Properties props = new Properties();
    props.put("bootstrap.servers", KAFKA.getBootstrapServers());
    try (AdminClient admin = AdminClient.create(props)) {
      admin
          .createTopics(
              List.of(
                  new NewTopic("alerts.detected", 6, (short) 1),
                  new NewTopic("alerts.detected.dlq", 1, (short) 1)))
          .all()
          .get(30, TimeUnit.SECONDS);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static void declareRabbitTopology() {
    try (Channel channel = rabbitChannel()) {
      channel.exchangeDeclare("alerts.notify", "topic", true);
      for (String[] q :
          new String[][] {{"notify.critical", "alert.critical"}, {"notify.all", "alert.*"}}) {
        channel.queueDeclare(q[0], true, false, false, Map.of());
        channel.queueBind(q[0], "alerts.notify", q[1]);
      }
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static void createQueues() {
    try (SqsClient sqs = sqsClient()) {
      sqs.createQueue(b -> b.queueName("alert-escalations"));
    }
  }

  private static void createCassandraSchema() {
    try (CqlSession session = cassandraSession(null)) {
      session.execute(
          "CREATE KEYSPACE IF NOT EXISTS vitalstream WITH replication = {'class':'SimpleStrategy','replication_factor':1}");
      session.execute(
          "CREATE TABLE IF NOT EXISTS vitalstream.vitals_raw (patient_id text, day date, observed_at timestamp, metric text, event_id uuid, value double, unit text, device_id text,"
              + " PRIMARY KEY ((patient_id, day), observed_at, metric, event_id))"
              + " WITH CLUSTERING ORDER BY (observed_at DESC, metric ASC, event_id ASC)");
      session.execute(
          "CREATE TABLE IF NOT EXISTS vitalstream.vitals_by_minute (patient_id text, metric text, day date, window_start timestamp, min double, max double, avg double, count int,"
              + " PRIMARY KEY ((patient_id, metric, day), window_start)) WITH CLUSTERING ORDER BY (window_start DESC)");
    }
  }
}
