package com.yaytsa.server.config;

import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * JPA and database persistence configuration for the media server. Configures entity scanning,
 * repositories, and transaction management.
 */
@Configuration
@EnableTransactionManagement
@EnableJpaRepositories(basePackages = "com.yaytsa.server.infrastructure.persistence.repository")
@EntityScan(basePackages = "com.yaytsa.server.infrastructure.persistence.entity")
@EnableJpaAuditing
public class PersistenceConfig {

  /**
   * Additional JPA configuration can be added here: - Custom naming strategies - Query timeout
   * configurations - Connection pool tuning - Custom converters
   */
}
