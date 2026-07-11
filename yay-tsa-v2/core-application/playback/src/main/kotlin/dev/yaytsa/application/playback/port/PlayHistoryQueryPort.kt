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
        includeAudiobooks: Boolean = false,
    ): List<PlayHistoryEvent>

    fun historyPage(
        userId: UserId,
        since: Instant?,
        until: Instant?,
        source: String?,
        limit: Int,
        offset: Int,
        includeAudiobooks: Boolean = false,
    ): List<PlayHistoryEvent>

    fun historyCount(
        userId: UserId,
        since: Instant?,
        until: Instant?,
        source: String?,
        includeAudiobooks: Boolean = false,
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
