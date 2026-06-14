package dev.yaytsa.persistence.playback.adapter

import dev.yaytsa.application.playback.SavedPlayQueue
import dev.yaytsa.application.playback.port.SavedPlayQueueRepository
import dev.yaytsa.persistence.playback.entity.SavedPlayQueueEntity
import dev.yaytsa.persistence.playback.jpa.SavedPlayQueueJpaRepository
import dev.yaytsa.shared.UserId
import org.springframework.stereotype.Repository

@Repository
class JpaSavedPlayQueueRepository(
    private val jpa: SavedPlayQueueJpaRepository,
) : SavedPlayQueueRepository {
    override fun find(userId: UserId): SavedPlayQueue? = jpa.findById(userId.value).orElse(null)?.toDomain()

    override fun save(queue: SavedPlayQueue) {
        jpa.save(
            SavedPlayQueueEntity(
                userId = queue.userId,
                trackIds = queue.trackIds,
                currentTrackId = queue.currentTrackId,
                positionMs = queue.positionMs,
                changedAt = queue.changedAt,
                changedBy = queue.changedBy,
            ),
        )
    }

    private fun SavedPlayQueueEntity.toDomain(): SavedPlayQueue =
        SavedPlayQueue(
            userId = userId,
            trackIds = trackIds,
            currentTrackId = currentTrackId,
            positionMs = positionMs,
            changedAt = changedAt,
            changedBy = changedBy,
        )
}
