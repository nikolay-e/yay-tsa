package dev.yaytsa.persistence.adaptive.jpa

import dev.yaytsa.persistence.adaptive.entity.PlaybackSignalEntity
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface PlaybackSignalJpaRepository : JpaRepository<PlaybackSignalEntity, UUID> {
    fun findBySessionIdOrderByCreatedAtDesc(
        sessionId: UUID,
        pageable: Pageable,
    ): List<PlaybackSignalEntity>
}
