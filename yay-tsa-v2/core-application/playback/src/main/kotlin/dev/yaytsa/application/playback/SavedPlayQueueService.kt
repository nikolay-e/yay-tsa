package dev.yaytsa.application.playback

import dev.yaytsa.application.playback.port.SavedPlayQueueRepository
import dev.yaytsa.shared.UserId
import java.time.Instant

class SavedPlayQueueService(
    private val repository: SavedPlayQueueRepository,
) {
    fun save(
        userId: UserId,
        trackIds: List<String>,
        currentTrackId: String?,
        positionMs: Long,
        changedBy: String?,
        requestTime: Instant,
    ): SavedPlayQueue {
        val snapshot =
            SavedPlayQueue(
                userId = userId.value,
                trackIds = trackIds,
                currentTrackId = currentTrackId?.takeIf { it in trackIds },
                positionMs = positionMs.coerceAtLeast(0),
                changedAt = requestTime,
                changedBy = changedBy,
            )
        repository.save(snapshot)
        return snapshot
    }

    fun find(userId: UserId): SavedPlayQueue? = repository.find(userId)
}
