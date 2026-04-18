package dev.yaytsa.persistence.library

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

@SpringBootApplication
@EntityScan(basePackages = ["dev.yaytsa.persistence.library.entity"])
@EnableJpaRepositories(basePackages = ["dev.yaytsa.persistence.library.repository"])
class LibraryTestApplication

@SpringBootTest(classes = [LibraryTestApplication::class])
@Testcontainers
abstract class LibraryPersistenceTestBase {
    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer("postgres:16-alpine")
                .withDatabaseName("yaytsa_test")
                .withUsername("test")
                .withPassword("test")

        @DynamicPropertySource
        @JvmStatic
        fun configureProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
            registry.add("spring.flyway.locations") { "classpath:db/library" }
            registry.add("spring.flyway.schemas") { "core_v2_library" }
            registry.add("spring.jpa.hibernate.ddl-auto") { "validate" }
            registry.add("spring.jpa.properties.hibernate.default_schema") { "core_v2_library" }
        }
    }
}
