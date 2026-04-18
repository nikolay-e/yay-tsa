package dev.yaytsa.persistence.playback.jpa

import dev.yaytsa.persistence.playback.entity.PlaybackSessionEntity
import dev.yaytsa.persistence.playback.entity.PlaybackSessionEntityId
import org.springframework.data.jpa.repository.JpaRepository

interface PlaybackSessionJpaRepository : JpaRepository<PlaybackSessionEntity, PlaybackSessionEntityId>
