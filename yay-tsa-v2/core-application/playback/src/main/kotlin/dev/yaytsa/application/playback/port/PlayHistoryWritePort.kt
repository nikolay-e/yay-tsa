package dev.yaytsa.application.playback.port

import dev.yaytsa.shared.TrackId
import dev.yaytsa.shared.UserId
import java.time.Instant

interface PlayHistoryWritePort {
    @Suppress("LongParameterList")
    fun record(
        userId: UserId,
        trackId: TrackId,
        startedAt: Instant,
        durationMs: Long?,
        playedMs: Long?,
        completed: Boolean,
        skipped: Boolean,
        source: String? = null,
        deviceId: String? = null,
    )
}
