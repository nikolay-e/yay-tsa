package dev.yaytsa.persistence.adaptive.adapter

import dev.yaytsa.application.adaptive.port.PlaybackSignalWritePort
import dev.yaytsa.domain.adaptive.AdaptiveQueueEntryId
import dev.yaytsa.domain.adaptive.ListeningSessionId
import dev.yaytsa.persistence.adaptive.entity.PlaybackSignalEntity
import dev.yaytsa.persistence.adaptive.jpa.PlaybackSignalJpaRepository
import dev.yaytsa.shared.TrackId
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Repository
class JpaPlaybackSignalWritePort(
    private val jpa: PlaybackSignalJpaRepository,
) : PlaybackSignalWritePort {
    override fun save(
        id: String,
        sessionId: ListeningSessionId,
        trackId: TrackId,
        queueEntryId: AdaptiveQueueEntryId?,
        signalType: String,
        context: String?,
        createdAt: Instant,
    ) {
        jpa.save(
            PlaybackSignalEntity(
                id = UUID.fromString(id),
                sessionId = UUID.fromString(sessionId.value),
                trackId = UUID.fromString(trackId.value),
                queueEntryId = queueEntryId?.let { UUID.fromString(it.value) },
                signalType = signalType,
                context = context,
                createdAt = createdAt,
            ),
        )
    }
}
