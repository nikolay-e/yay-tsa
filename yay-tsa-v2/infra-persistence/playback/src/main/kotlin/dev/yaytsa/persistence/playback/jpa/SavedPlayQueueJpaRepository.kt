package dev.yaytsa.persistence.playback.jpa

import dev.yaytsa.persistence.playback.entity.SavedPlayQueueEntity
import org.springframework.data.jpa.repository.JpaRepository

interface SavedPlayQueueJpaRepository : JpaRepository<SavedPlayQueueEntity, String>
