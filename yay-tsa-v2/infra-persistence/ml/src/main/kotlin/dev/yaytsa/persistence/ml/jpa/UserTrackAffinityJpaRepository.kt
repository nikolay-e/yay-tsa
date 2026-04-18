package dev.yaytsa.persistence.ml.jpa

import dev.yaytsa.persistence.ml.entity.UserTrackAffinityEntity
import dev.yaytsa.persistence.ml.entity.UserTrackAffinityId
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface UserTrackAffinityJpaRepository : JpaRepository<UserTrackAffinityEntity, UserTrackAffinityId> {
    fun findByUserIdAndTrackId(
        userId: UUID,
        trackId: UUID,
    ): UserTrackAffinityEntity?

    fun findByUserIdOrderByAffinityScoreDesc(
        userId: UUID,
        pageable: Pageable,
    ): List<UserTrackAffinityEntity>
}
