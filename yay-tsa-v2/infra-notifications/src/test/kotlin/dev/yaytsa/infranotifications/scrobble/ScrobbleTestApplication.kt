package dev.yaytsa.infranotifications.scrobble

import dev.yaytsa.application.auth.AuthQueries
import dev.yaytsa.application.auth.port.UserRepository
import dev.yaytsa.application.library.port.LibraryQueryPort
import dev.yaytsa.application.shared.port.Clock
import dev.yaytsa.testkit.InMemoryLibraryQueryPort
import dev.yaytsa.testkit.InMemoryUserRepository
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.context.annotation.Bean
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import java.time.Instant

@SpringBootApplication
@EntityScan(basePackages = ["dev.yaytsa.persistence.playback.entity"])
@EnableJpaRepositories(basePackages = ["dev.yaytsa.persistence.playback.jpa"])
class ScrobbleTestApplication {
    @Bean
    fun clock(): Clock =
        object : Clock {
            override fun now(): Instant = Instant.now()
        }

    @Bean
    fun meterRegistry(): MeterRegistry = SimpleMeterRegistry()

    @Bean
    fun libraryQueryPort(): LibraryQueryPort = InMemoryLibraryQueryPort()

    @Bean
    fun userRepository(): UserRepository = InMemoryUserRepository()

    @Bean
    fun authQueries(userRepository: UserRepository): AuthQueries = AuthQueries(userRepository)
}
