package dev.yaytsa.persistence.karaoke.jpa

import dev.yaytsa.persistence.karaoke.entity.KaraokeAssetEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface KaraokeAssetJpaRepository : JpaRepository<KaraokeAssetEntity, UUID> {
    fun findByReadyAtIsNotNull(): List<KaraokeAssetEntity>
}
