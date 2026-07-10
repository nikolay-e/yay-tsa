package dev.yaytsa.application.playback.port

import dev.yaytsa.shared.TrackId
import dev.yaytsa.shared.UserId
import java.time.Instant

interface PlayHistoryQueryPort {
    fun mostPlayedTrackCounts(
        userId: UserId,
        limit: Int,
    ): List<TrackPlayCount>

    fun recentlyPlayedTrackIds(
        userId: UserId,
        limit: Int,
    ): List<TrackId>

    fun playCountsByTrackIds(trackIds: Collection<TrackId>): Map<TrackId, Long>

    fun eventsInWindow(
        userId: UserId,
        since: Instant,
        until: Instant,
    ): List<PlayHistoryEvent>

    fun historyPage(
        userId: UserId,
        since: Instant?,
        until: Instant?,
        source: String?,
        limit: Int,
        offset: Int,
    ): List<PlayHistoryEvent>

    fun historyCount(
        userId: UserId,
        since: Instant?,
        until: Instant?,
        source: String?,
    ): Long
}

data class TrackPlayCount(
    val trackId: TrackId,
    val playCount: Long,
)

data class PlayHistoryEvent(
    val trackId: TrackId,
    val startedAt: Instant,
    val durationMs: Long?,
    val playedMs: Long?,
    val completed: Boolean,
    val skipped: Boolean,
    val source: String?,
    val deviceId: String?,
)
