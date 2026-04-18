package dev.yaytsa.application.playback

import dev.yaytsa.application.playback.port.PlaybackSessionRepository
import dev.yaytsa.domain.playback.PlaybackSessionAggregate
import dev.yaytsa.domain.playback.SessionId
import dev.yaytsa.shared.UserId

class PlaybackQueries(
    private val sessionRepo: PlaybackSessionRepository,
) {
    fun getPlaybackState(
        userId: UserId,
        sessionId: SessionId,
    ): PlaybackSessionAggregate? = sessionRepo.find(userId, sessionId)
}
