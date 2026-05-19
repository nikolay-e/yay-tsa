package dev.yaytsa.adaptershared

import dev.yaytsa.application.shared.port.Clock
import dev.yaytsa.shared.AggregateVersion
import dev.yaytsa.shared.CommandContext
import dev.yaytsa.shared.IdempotencyKey
import dev.yaytsa.shared.ProtocolId
import dev.yaytsa.shared.UserId
import java.util.UUID

class AdapterCommandContextFactory(
    private val protocolId: ProtocolId,
    private val clock: Clock,
) {
    fun create(
        userId: UserId,
        expectedVersion: AggregateVersion = AggregateVersion.INITIAL,
    ): CommandContext =
        CommandContext(
            userId = userId,
            protocolId = protocolId,
            requestTime = clock.now(),
            idempotencyKey = IdempotencyKey(UUID.randomUUID().toString()),
            expectedVersion = expectedVersion,
        )
}
