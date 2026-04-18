package dev.yaytsa.application.adaptive.port

import dev.yaytsa.domain.adaptive.AdaptiveQueueEntryId
import dev.yaytsa.domain.adaptive.ListeningSessionId
import dev.yaytsa.shared.TrackId
import java.time.Instant

interface PlaybackSignalWritePort {
    fun save(
        id: String,
        sessionId: ListeningSessionId,
        trackId: TrackId,
        queueEntryId: AdaptiveQueueEntryId?,
        signalType: String,
        context: String?,
        createdAt: Instant,
    )
}
