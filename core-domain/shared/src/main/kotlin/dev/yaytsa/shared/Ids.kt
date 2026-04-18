package dev.yaytsa.shared

/**
 * Shared identity types used across all bounded contexts.
 * Context-specific IDs (SessionId, QueueEntryId, etc.) live in their own contexts.
 */

@JvmInline value class UserId(
    val value: String,
)

@JvmInline value class EntityId(
    val value: String,
)

@JvmInline value class TrackId(
    val value: String,
)

@JvmInline value class AlbumId(
    val value: String,
)

@JvmInline value class ArtistId(
    val value: String,
)

@JvmInline value class GenreId(
    val value: String,
)

@JvmInline value class ImageId(
    val value: String,
)

@JvmInline value class ProtocolId(
    val value: String,
)

@JvmInline value class IdempotencyKey(
    val value: String,
)

@JvmInline value class AggregateVersion(
    val value: Long,
) {
    fun next(): AggregateVersion = AggregateVersion(value + 1)

    companion object {
        val INITIAL = AggregateVersion(0)
    }
}
