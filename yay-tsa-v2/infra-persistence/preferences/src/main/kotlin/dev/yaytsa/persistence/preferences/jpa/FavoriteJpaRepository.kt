package dev.yaytsa.persistence.preferences.jpa

import dev.yaytsa.persistence.preferences.entity.FavoriteEntity
import dev.yaytsa.persistence.preferences.entity.FavoriteEntityId
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface FavoriteJpaRepository : JpaRepository<FavoriteEntity, FavoriteEntityId> {
    fun findByUserIdOrderByPosition(userId: String): List<FavoriteEntity>

    @Query("select f.trackId from FavoriteEntity f where f.userId = :userId")
    fun findTrackIdsByUserId(userId: String): List<String>

    fun deleteByUserId(userId: String)
}
