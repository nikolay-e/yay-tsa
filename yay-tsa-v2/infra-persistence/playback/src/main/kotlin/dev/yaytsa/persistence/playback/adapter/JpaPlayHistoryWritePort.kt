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
    @Value("\${yaytsa.playback.play-history-dedup-window-seconds:1800}")
    private val dedupWindowSeconds: Long,
    @Value("\${yaytsa.playback.play-history-dedup-duration-tolerance-ms:5000}")
    private val dedupDurationToleranceMs: Long,
) : PlayHistoryWritePort {
    @Transactional
    @Suppress("LongParameterList")
    override fun record(
        userId: UserId,
        trackId: TrackId,
        startedAt: Instant,
        durationMs: Long?,
        playedMs: Long?,
        completed: Boolean,
        skipped: Boolean,
        source: String?,
        deviceId: String?,
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
            durationToleranceMs = dedupDurationToleranceMs,
            source = source?.take(SOURCE_MAX_LENGTH),
            deviceId = deviceId?.take(DEVICE_ID_MAX_LENGTH),
        )
    }

    companion object {
        private const val SOURCE_MAX_LENGTH = 16
        private const val DEVICE_ID_MAX_LENGTH = 64
    }
}
