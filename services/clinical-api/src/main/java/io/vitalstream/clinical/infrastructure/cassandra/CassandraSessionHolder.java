package io.vitalstream.clinical.infrastructure.cassandra;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.config.DefaultDriverOption;
import com.datastax.oss.driver.api.core.config.DriverConfigLoader;
import io.vitalstream.clinical.infrastructure.config.ClinicalProperties;
import jakarta.annotation.PreDestroy;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Opens the Cassandra session on first use. Cassandra is slow to start and only serves the history
 * endpoint, so the rest of the service (alert intake, SSE, search) must boot and run without it.
 */
@Component
public class CassandraSessionHolder {

  private final ClinicalProperties.Cassandra settings;
  private volatile CqlSession session;

  public CassandraSessionHolder(ClinicalProperties properties) {
    this.settings = properties.cassandra();
  }

  public CqlSession session() {
    CqlSession current = session;
    if (current == null) {
      synchronized (this) {
        if (session == null) {
          session = connect();
        }
        current = session;
      }
    }
    return current;
  }

  private CqlSession connect() {
    List<InetSocketAddress> contactPoints =
        Arrays.stream(settings.contactPoints().split(","))
            .map(String::trim)
            .map(CassandraSessionHolder::address)
            .toList();
    return CqlSession.builder()
        .addContactPoints(contactPoints)
        .withLocalDatacenter(settings.datacenter())
        .withKeyspace(settings.keyspace())
        .withConfigLoader(
            DriverConfigLoader.programmaticBuilder()
                .withDuration(DefaultDriverOption.REQUEST_TIMEOUT, Duration.ofSeconds(5))
                .withDuration(DefaultDriverOption.CONNECTION_CONNECT_TIMEOUT, Duration.ofSeconds(5))
                .build())
        .build();
  }

  private static InetSocketAddress address(String hostAndPort) {
    int colon = hostAndPort.lastIndexOf(':');
    if (colon < 0) {
      return new InetSocketAddress(hostAndPort, 9042);
    }
    return new InetSocketAddress(
        hostAndPort.substring(0, colon), Integer.parseInt(hostAndPort.substring(colon + 1)));
  }

  @PreDestroy
  void close() {
    CqlSession current = session;
    if (current != null) {
      current.close();
    }
  }
}
