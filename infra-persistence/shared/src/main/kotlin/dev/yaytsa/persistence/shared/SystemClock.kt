package dev.yaytsa.persistence.shared

import dev.yaytsa.application.shared.port.Clock
import org.springframework.stereotype.Component
import java.time.Instant

@Component
class SystemClock : Clock {
    override fun now(): Instant = Instant.now()
}
