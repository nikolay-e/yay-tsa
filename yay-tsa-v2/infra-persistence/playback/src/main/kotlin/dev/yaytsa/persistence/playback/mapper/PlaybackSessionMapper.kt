package dev.yaytsa.persistence.playback.mapper

import dev.yaytsa.domain.playback.DeviceId
import dev.yaytsa.domain.playback.PlaybackLease
import dev.yaytsa.domain.playback.PlaybackSessionAggregate
import dev.yaytsa.domain.playback.PlaybackState
import dev.yaytsa.domain.playback.QueueEntry
import dev.yaytsa.domain.playback.QueueEntryId
import dev.yaytsa.domain.playback.SessionId
import dev.yaytsa.persistence.playback.entity.PlaybackSessionEntity
import dev.yaytsa.persistence.playback.entity.QueueEntryEntity
import dev.yaytsa.shared.AggregateVersion
import dev.yaytsa.shared.TrackId
import dev.yaytsa.shared.UserId
import java.time.Duration

fun toDomain(
    entity: PlaybackSessionEntity,
    queueEntries: List<QueueEntryEntity>,
): PlaybackSessionAggregate =
    PlaybackSessionAggregate(
        userId = UserId(entity.userId),
        sessionId = SessionId(entity.sessionId),
        queue = queueEntries.sortedBy { it.position }.map { it.toDomain() },
        currentEntryId = entity.currentEntryId?.let { QueueEntryId(it) },
        playbackState = PlaybackState.valueOf(entity.playbackState),
        lastKnownPosition = Duration.ofMillis(entity.lastKnownPositionMs),
        lastKnownAt = entity.lastKnownAt,
        lease = toLease(entity),
        version = AggregateVersion(entity.version),
    )

private fun toLease(entity: PlaybackSessionEntity): PlaybackLease? {
    val owner = entity.leaseOwner ?: return null
    val expiresAt = entity.leaseExpiresAt ?: return null
    return PlaybackLease(
        owner = DeviceId(owner),
        expiresAt = expiresAt,
    )
}

fun QueueEntryEntity.toDomain(): QueueEntry =
    QueueEntry(
        id = QueueEntryId(entryId),
        trackId = TrackId(trackId),
    )

fun PlaybackSessionAggregate.toEntity(): PlaybackSessionEntity =
    PlaybackSessionEntity(
        userId = userId.value,
        sessionId = sessionId.value,
        currentEntryId = currentEntryId?.value,
        playbackState = playbackState.name,
        lastKnownPositionMs = lastKnownPosition.toMillis(),
        lastKnownAt = lastKnownAt,
        leaseOwner = lease?.owner?.value,
        leaseExpiresAt = lease?.expiresAt,
        version = version.value,
    )

fun PlaybackSessionAggregate.toQueueEntryEntities(): List<QueueEntryEntity> =
    queue.mapIndexed { index, entry ->
        QueueEntryEntity(
            userId = userId.value,
            sessionId = sessionId.value,
            entryId = entry.id.value,
            trackId = entry.trackId.value,
            position = index,
        )
    }
