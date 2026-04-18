package dev.yaytsa.adapteropensubsonic

import dev.yaytsa.application.shared.port.Clock
import dev.yaytsa.shared.AggregateVersion
import dev.yaytsa.shared.CommandContext
import dev.yaytsa.shared.IdempotencyKey
import dev.yaytsa.shared.ProtocolId
import dev.yaytsa.shared.UserId
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class SubsonicCommandContextFactory(
    private val clock: Clock,
) {
    fun create(
        userId: UserId,
        expectedVersion: AggregateVersion,
    ): CommandContext =
        CommandContext(
            userId = userId,
            protocolId = SUBSONIC_PROTOCOL,
            requestTime = clock.now(),
            idempotencyKey = IdempotencyKey(UUID.randomUUID().toString()),
            expectedVersion = expectedVersion,
        )

    companion object {
        val SUBSONIC_PROTOCOL = ProtocolId("OPENSUBSONIC")
    }
}
