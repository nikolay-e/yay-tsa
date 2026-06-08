package dev.yaytsa.application.playback.port

import dev.yaytsa.application.playback.ResumePosition
import dev.yaytsa.shared.UserId

interface ResumePositionRepository {
    fun find(
        userId: UserId,
        itemId: String,
    ): ResumePosition?

    fun findByItemIds(
        userId: UserId,
        itemIds: Set<String>,
    ): Map<String, ResumePosition>

    fun findAll(userId: UserId): List<ResumePosition>

    fun save(resume: ResumePosition)
}
