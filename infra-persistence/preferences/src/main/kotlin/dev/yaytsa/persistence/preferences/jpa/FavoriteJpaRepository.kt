package dev.yaytsa.persistence.preferences.jpa

import dev.yaytsa.persistence.preferences.entity.FavoriteEntity
import dev.yaytsa.persistence.preferences.entity.FavoriteEntityId
import org.springframework.data.jpa.repository.JpaRepository

interface FavoriteJpaRepository : JpaRepository<FavoriteEntity, FavoriteEntityId> {
    fun findByUserIdOrderByPosition(userId: String): List<FavoriteEntity>

    fun deleteByUserId(userId: String)
}
