package dev.yaytsa.shared

import java.time.Instant

/**
 * Context for command execution. Contains only identity and temporal information.
 *
 * deviceId and sessionId are NOT here — they belong to the command itself,
 * preventing split authority between context and command.
 */
data class CommandContext(
    val userId: UserId,
    val protocolId: ProtocolId,
    val requestTime: Instant,
    val idempotencyKey: IdempotencyKey,
    val expectedVersion: AggregateVersion,
)
