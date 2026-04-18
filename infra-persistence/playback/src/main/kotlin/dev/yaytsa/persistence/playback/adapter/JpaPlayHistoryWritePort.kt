package dev.yaytsa.persistence.playback.adapter

import dev.yaytsa.application.playback.port.PlayHistoryWritePort
import dev.yaytsa.persistence.playback.entity.PlayHistoryEntity
import dev.yaytsa.persistence.playback.jpa.PlayHistoryJpaRepository
import dev.yaytsa.shared.TrackId
import dev.yaytsa.shared.UserId
import org.springframework.stereotype.Repository
import java.time.Instant

@Repository
class JpaPlayHistoryWritePort(
    private val jpa: PlayHistoryJpaRepository,
) : PlayHistoryWritePort {
    override fun record(
        userId: UserId,
        trackId: TrackId,
        startedAt: Instant,
        durationMs: Long?,
        playedMs: Long?,
        completed: Boolean,
        skipped: Boolean,
    ) {
        jpa.save(
            PlayHistoryEntity(
                userId = userId.value,
                itemId = trackId.value,
                startedAt = startedAt,
                durationMs = durationMs ?: 0,
                playedMs = playedMs ?: 0,
                completed = completed,
                skipped = skipped,
            ),
        )
    }
}
