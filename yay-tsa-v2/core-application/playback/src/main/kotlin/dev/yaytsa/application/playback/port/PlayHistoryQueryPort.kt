package dev.yaytsa.application.playback.port

import dev.yaytsa.shared.TrackId
import dev.yaytsa.shared.UserId

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
}

data class TrackPlayCount(
    val trackId: TrackId,
    val playCount: Long,
)
