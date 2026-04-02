package com.yaytsa.server.config;

import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@Configuration
@EnableTransactionManagement
@EnableJpaRepositories(basePackages = "com.yaytsa.server.infrastructure.persistence.repository")
@EntityScan(basePackages = "com.yaytsa.server.infrastructure.persistence.entity")
@EnableJpaAuditing
public class PersistenceConfig {}
