package dev.yaytsa.adaptershared

import dev.yaytsa.application.shared.port.Clock
import dev.yaytsa.shared.AggregateVersion
import dev.yaytsa.shared.CommandContext
import dev.yaytsa.shared.IdempotencyKey
import dev.yaytsa.shared.ProtocolId
import dev.yaytsa.shared.UserId
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
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
            idempotencyKey = resolveIdempotencyKey(),
            expectedVersion = expectedVersion,
        )

    private fun resolveIdempotencyKey(): IdempotencyKey {
        val clientKey = currentRequestHeader(IDEMPOTENCY_KEY_HEADER) ?: currentRequestHeader(LEGACY_IDEMPOTENCY_KEY_HEADER)
        return IdempotencyKey(clientKey ?: UUID.randomUUID().toString())
    }

    private fun currentRequestHeader(name: String): String? {
        val attributes = RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes ?: return null
        return attributes.request
            .getHeader(name)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    private companion object {
        const val IDEMPOTENCY_KEY_HEADER = "Idempotency-Key"
        const val LEGACY_IDEMPOTENCY_KEY_HEADER = "X-Idempotency-Key"
    }
}
