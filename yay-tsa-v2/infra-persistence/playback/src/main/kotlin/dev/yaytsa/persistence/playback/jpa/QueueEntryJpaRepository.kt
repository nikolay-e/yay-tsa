package dev.yaytsa.persistence.playback.jpa

import dev.yaytsa.persistence.playback.entity.QueueEntryEntity
import dev.yaytsa.persistence.playback.entity.QueueEntryEntityId
import org.springframework.data.jpa.repository.JpaRepository

interface QueueEntryJpaRepository : JpaRepository<QueueEntryEntity, QueueEntryEntityId> {
    fun findByUserIdAndSessionIdOrderByPosition(
        userId: String,
        sessionId: String,
    ): List<QueueEntryEntity>

    fun deleteByUserIdAndSessionId(
        userId: String,
        sessionId: String,
    )
}
