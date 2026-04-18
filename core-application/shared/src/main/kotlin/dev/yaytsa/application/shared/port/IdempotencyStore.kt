package dev.yaytsa.application.shared.port

import dev.yaytsa.shared.IdempotencyKey
import dev.yaytsa.shared.UserId

interface IdempotencyStore {
    fun find(
        userId: UserId,
        commandType: String,
        key: IdempotencyKey,
    ): StoredIdempotencyRecord?

    fun store(
        userId: UserId,
        commandType: String,
        key: IdempotencyKey,
        payloadHash: String,
        resultVersion: Long,
    )
}

data class StoredIdempotencyRecord(
    val payloadHash: String,
    val resultVersion: Long,
)
