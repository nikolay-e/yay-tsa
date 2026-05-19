package dev.yaytsa.testkit.persistence

import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

abstract class AbstractPersistenceTest {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun registerDataSourceProperties(registry: DynamicPropertyRegistry) {
            val container = SharedPostgresContainer.instance
            registry.add("spring.datasource.url") { SharedPostgresContainer.jdbcUrlWithUnspecifiedStringType }
            registry.add("spring.datasource.username") { container.username }
            registry.add("spring.datasource.password") { container.password }
            registry.add("spring.flyway.enabled") { "true" }
        }
    }
}
