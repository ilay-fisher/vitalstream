package io.vitalstream.clinical.integration;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * A Postgres-only fixture for tests of SQL and migrations: starts in seconds, unlike {@link Infra}
 * which brings up the whole platform. Each call to {@link #freshDatabase()} gives an empty database
 * so tests cannot see each other's rows.
 */
final class PostgresSupport {

  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg16"))
          .withDatabaseName("postgres")
          .withUsername("vitalstream")
          .withPassword("vitalstream");

  private static int databases;

  private PostgresSupport() {}

  /** An empty database on the shared container. */
  static synchronized DataSource freshDatabase() {
    if (!POSTGRES.isRunning()) {
      POSTGRES.start();
    }
    String name = "it_" + (++databases);
    try (var connection = adminSource().getConnection();
        var statement = connection.createStatement()) {
      statement.execute("CREATE DATABASE " + name);
    } catch (java.sql.SQLException e) {
      throw new IllegalStateException(e);
    }
    PGSimpleDataSource source = new PGSimpleDataSource();
    source.setUrl(POSTGRES.getJdbcUrl().replaceFirst("/postgres", "/" + name));
    source.setUser(POSTGRES.getUsername());
    source.setPassword(POSTGRES.getPassword());
    return source;
  }

  /** Flyway as the application configures it, optionally stopping at {@code targetVersion}. */
  static void migrate(DataSource source, String targetVersion) {
    var configuration =
        Flyway.configure()
            .dataSource(source)
            .schemas("clinical")
            .defaultSchema("clinical")
            .createSchemas(true)
            .baselineOnMigrate(true)
            .baselineVersion("0");
    if (targetVersion != null) {
      configuration.target(targetVersion);
    }
    configuration.load().migrate();
  }

  static JdbcClient client(DataSource source) {
    return JdbcClient.create(source);
  }

  private static DataSource adminSource() {
    PGSimpleDataSource source = new PGSimpleDataSource();
    source.setUrl(POSTGRES.getJdbcUrl());
    source.setUser(POSTGRES.getUsername());
    source.setPassword(POSTGRES.getPassword());
    return source;
  }
}
