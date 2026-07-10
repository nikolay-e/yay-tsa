package dev.yaytsa.persistence.playback.adapter

import dev.yaytsa.application.playback.port.PlayHistoryEvent
import dev.yaytsa.application.playback.port.PlayHistoryQueryPort
import dev.yaytsa.application.playback.port.TrackPlayCount
import dev.yaytsa.persistence.playback.entity.PlayHistoryEntity
import dev.yaytsa.persistence.playback.jpa.PlayHistoryJpaRepository
import dev.yaytsa.shared.TrackId
import dev.yaytsa.shared.UserId
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Repository
@Transactional(readOnly = true)
class JpaPlayHistoryQueryPort(
    private val jpa: PlayHistoryJpaRepository,
) : PlayHistoryQueryPort {
    override fun mostPlayedTrackCounts(
        userId: UserId,
        limit: Int,
    ): List<TrackPlayCount> =
        jpa
            .findMostPlayedItemCountsByUser(userId.value, maxOf(limit, 1))
            .map { TrackPlayCount(TrackId(it.getItemId()), it.getPlayCount()) }

    override fun recentlyPlayedTrackIds(
        userId: UserId,
        limit: Int,
    ): List<TrackId> = jpa.findRecentlyPlayedItemIdsByUser(userId.value, maxOf(limit, 1)).map { TrackId(it) }

    override fun playCountsByTrackIds(trackIds: Collection<TrackId>): Map<TrackId, Long> {
        if (trackIds.isEmpty()) return emptyMap()
        return jpa
            .countPlaysByItemIds(trackIds.map { it.value })
            .associate { TrackId(it.getItemId()) to it.getPlayCount() }
    }

    override fun eventsInWindow(
        userId: UserId,
        since: Instant,
        until: Instant,
    ): List<PlayHistoryEvent> = jpa.findEventsInWindow(userId.value, since, until).map { it.toEvent() }

    override fun historyPage(
        userId: UserId,
        since: Instant?,
        until: Instant?,
        source: String?,
        limit: Int,
        offset: Int,
    ): List<PlayHistoryEvent> =
        jpa
            .findHistoryPage(userId.value, since, until, source, maxOf(limit, 1), maxOf(offset, 0))
            .map { it.toEvent() }

    override fun historyCount(
        userId: UserId,
        since: Instant?,
        until: Instant?,
        source: String?,
    ): Long = jpa.countHistory(userId.value, since, until, source)

    private fun PlayHistoryEntity.toEvent(): PlayHistoryEvent =
        PlayHistoryEvent(
            trackId = TrackId(itemId),
            startedAt = startedAt,
            durationMs = durationMs.takeIf { it > 0 },
            playedMs = playedMs,
            completed = completed,
            skipped = skipped,
            source = source,
            deviceId = deviceId,
        )
}
