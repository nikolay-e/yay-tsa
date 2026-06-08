package dev.yaytsa.persistence.playback.adapter

import dev.yaytsa.application.playback.ResumePosition
import dev.yaytsa.application.playback.ResumeStatus
import dev.yaytsa.application.playback.port.ResumePositionRepository
import dev.yaytsa.persistence.playback.entity.ResumePositionEntity
import dev.yaytsa.persistence.playback.entity.ResumePositionEntityId
import dev.yaytsa.persistence.playback.jpa.ResumePositionJpaRepository
import dev.yaytsa.shared.UserId
import org.springframework.stereotype.Repository

@Repository
class JpaResumePositionRepository(
    private val jpa: ResumePositionJpaRepository,
) : ResumePositionRepository {
    override fun find(
        userId: UserId,
        itemId: String,
    ): ResumePosition? = jpa.findById(ResumePositionEntityId(userId.value, itemId)).orElse(null)?.toDomain()

    override fun findByItemIds(
        userId: UserId,
        itemIds: Set<String>,
    ): Map<String, ResumePosition> =
        if (itemIds.isEmpty()) {
            emptyMap()
        } else {
            jpa.findByUserIdAndItemIdIn(userId.value, itemIds).associate { it.itemId to it.toDomain() }
        }

    override fun findAll(userId: UserId): List<ResumePosition> = jpa.findByUserId(userId.value).map { it.toDomain() }

    override fun save(resume: ResumePosition) {
        jpa.save(
            ResumePositionEntity(
                userId = resume.userId,
                itemId = resume.itemId,
                positionMs = resume.positionMs,
                runTimeMs = resume.runTimeMs,
                status = resume.status.wireValue(),
                sourceEvent = resume.sourceEvent,
                updatedAt = resume.updatedAt,
            ),
        )
    }

    private fun ResumePositionEntity.toDomain(): ResumePosition =
        ResumePosition(
            userId = userId,
            itemId = itemId,
            positionMs = positionMs,
            runTimeMs = runTimeMs,
            status = ResumeStatus.fromWire(status),
            sourceEvent = sourceEvent,
            updatedAt = updatedAt,
        )
}
