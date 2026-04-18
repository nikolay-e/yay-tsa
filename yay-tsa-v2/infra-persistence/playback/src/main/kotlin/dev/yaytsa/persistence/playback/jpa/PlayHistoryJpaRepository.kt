package dev.yaytsa.persistence.playback.jpa

import dev.yaytsa.persistence.playback.entity.PlayHistoryEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface PlayHistoryJpaRepository : JpaRepository<PlayHistoryEntity, UUID>
