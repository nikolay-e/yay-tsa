package dev.yaytsa.application.playback

import dev.yaytsa.application.playback.port.ResumePositionRepository
import dev.yaytsa.shared.UserId
import java.time.Instant

class ResumePositionService(
    private val repository: ResumePositionRepository,
) {
    fun record(
        userId: UserId,
        itemId: String,
        positionMs: Long,
        runTimeMs: Long,
        sourceEvent: String,
        requestTime: Instant,
    ): ResumePosition {
        val existing = repository.find(userId, itemId)
        val incoming =
            ResumePosition(
                userId = userId.value,
                itemId = itemId,
                positionMs = positionMs.coerceAtLeast(0),
                runTimeMs = runTimeMs.coerceAtLeast(0),
                status = ResumeStatus.IN_PROGRESS,
                sourceEvent = sourceEvent,
                updatedAt = requestTime,
            )
        val merged = mergeResume(existing, incoming)
        repository.save(merged)
        return merged
    }

    fun markFinished(
        userId: UserId,
        itemId: String,
        requestTime: Instant,
    ): ResumePosition {
        val existing =
            repository.find(userId, itemId)
                ?: ResumePosition(
                    userId = userId.value,
                    itemId = itemId,
                    positionMs = 0,
                    runTimeMs = 0,
                    status = ResumeStatus.FINISHED,
                    sourceEvent = ResumeSource.STOPPED,
                    updatedAt = requestTime,
                )
        val finished =
            existing.copy(
                status = ResumeStatus.FINISHED,
                sourceEvent = ResumeSource.STOPPED,
                updatedAt = requestTime,
            )
        repository.save(finished)
        return finished
    }

    fun restart(
        userId: UserId,
        itemId: String,
        requestTime: Instant,
    ): ResumePosition {
        val existing = repository.find(userId, itemId)
        val relistening =
            ResumePosition(
                userId = userId.value,
                itemId = itemId,
                positionMs = 0,
                runTimeMs = existing?.runTimeMs ?: 0,
                status = ResumeStatus.RELISTENING,
                sourceEvent = ResumeSource.SEEK,
                updatedAt = requestTime,
            )
        repository.save(relistening)
        return relistening
    }

    fun find(
        userId: UserId,
        itemId: String,
    ): ResumePosition? = repository.find(userId, itemId)

    fun findByItemIds(
        userId: UserId,
        itemIds: Set<String>,
    ): Map<String, ResumePosition> = if (itemIds.isEmpty()) emptyMap() else repository.findByItemIds(userId, itemIds)

    fun findAll(userId: UserId): List<ResumePosition> = repository.findAll(userId)
}
