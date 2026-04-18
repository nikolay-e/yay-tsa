package dev.yaytsa.application.playback.port

import dev.yaytsa.domain.playback.PlaybackSessionAggregate
import dev.yaytsa.domain.playback.SessionId
import dev.yaytsa.shared.UserId

interface PlaybackSessionRepository {
    fun find(
        userId: UserId,
        sessionId: SessionId,
    ): PlaybackSessionAggregate?

    fun save(aggregate: PlaybackSessionAggregate)
}
