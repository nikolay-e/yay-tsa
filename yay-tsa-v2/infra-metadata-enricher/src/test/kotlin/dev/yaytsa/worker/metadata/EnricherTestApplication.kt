package dev.yaytsa.worker.metadata

import dev.yaytsa.application.shared.port.Clock
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.context.annotation.Bean
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import java.time.Instant

@SpringBootApplication
@EntityScan(basePackages = ["dev.yaytsa.persistence.library.entity"])
@EnableJpaRepositories(basePackages = ["dev.yaytsa.persistence.library.repository"])
class EnricherTestApplication {
    @Bean
    fun clock(): Clock =
        object : Clock {
            override fun now(): Instant = Instant.parse("2026-01-01T00:00:00Z")
        }
}
