package dev.yaytsa.persistence.playback.jpa

import dev.yaytsa.persistence.playback.entity.ResumePositionEntity
import dev.yaytsa.persistence.playback.entity.ResumePositionEntityId
import org.springframework.data.jpa.repository.JpaRepository

interface ResumePositionJpaRepository : JpaRepository<ResumePositionEntity, ResumePositionEntityId> {
    fun findByUserId(userId: String): List<ResumePositionEntity>

    fun findByUserIdAndItemIdIn(
        userId: String,
        itemIds: Collection<String>,
    ): List<ResumePositionEntity>
}
