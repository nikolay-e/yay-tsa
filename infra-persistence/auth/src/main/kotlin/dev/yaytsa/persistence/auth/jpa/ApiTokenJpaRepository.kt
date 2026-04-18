package dev.yaytsa.persistence.auth.jpa

import dev.yaytsa.persistence.auth.entity.ApiTokenEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface ApiTokenJpaRepository : JpaRepository<ApiTokenEntity, UUID> {
    fun findByUserId(userId: UUID): List<ApiTokenEntity>

    fun findByToken(token: String): ApiTokenEntity?

    fun findByTokenAndRevokedFalse(token: String): ApiTokenEntity?
}
