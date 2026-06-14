package dev.yaytsa.persistence.playback.adapter

import dev.yaytsa.application.playback.port.PlayHistoryWritePort
import dev.yaytsa.persistence.playback.jpa.PlayHistoryJpaRepository
import dev.yaytsa.shared.TrackId
import dev.yaytsa.shared.UserId
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

@Repository
class JpaPlayHistoryWritePort(
    private val jpa: PlayHistoryJpaRepository,
    @Value("\${yaytsa.playback.play-history-dedup-window-seconds:30}")
    private val dedupWindowSeconds: Long,
) : PlayHistoryWritePort {
    @Transactional
    override fun record(
        userId: UserId,
        trackId: TrackId,
        startedAt: Instant,
        durationMs: Long?,
        playedMs: Long?,
        completed: Boolean,
        skipped: Boolean,
    ) {
        jpa.insertUnlessRecentDuplicate(
            id = UUID.randomUUID(),
            userId = userId.value,
            itemId = trackId.value,
            startedAt = startedAt,
            durationMs = durationMs ?: 0,
            playedMs = playedMs ?: 0,
            completed = completed,
            skipped = skipped,
            recordedAt = Instant.now(),
            dedupWindowSeconds = dedupWindowSeconds,
        )
    }
}
