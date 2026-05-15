package com.yaytsa.server.integration;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfig {

  // Reuse only outside CI: CI runs are ephemeral and benefit from clean state per run; reuse there
  // risks cross-run contamination if a test forgets DatabaseCleaner.clean().
  private static final boolean REUSE_CONTAINER = !"true".equalsIgnoreCase(System.getenv("CI"));

  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(
              DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"))
          .withReuse(REUSE_CONTAINER);

  static {
    POSTGRES.start();
  }

  @Bean
  @ServiceConnection
  PostgreSQLContainer<?> postgresContainer() {
    return POSTGRES;
  }
}
