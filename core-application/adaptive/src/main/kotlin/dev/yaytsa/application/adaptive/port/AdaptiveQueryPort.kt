package dev.yaytsa.application.adaptive.port

import dev.yaytsa.domain.adaptive.AdaptiveQueueEntry
import dev.yaytsa.domain.adaptive.ListeningSession
import dev.yaytsa.domain.adaptive.ListeningSessionId
import dev.yaytsa.domain.adaptive.LlmDecision
import dev.yaytsa.domain.adaptive.PlaybackSignal
import dev.yaytsa.shared.UserId

interface AdaptiveQueryPort {
    companion object {
        const val MAX_QUERY_LIMIT = 1000
    }

    fun findAllActiveSessions(): List<ListeningSession>

    fun findActiveSession(userId: UserId): ListeningSession?

    fun findSession(sessionId: ListeningSessionId): ListeningSession?

    fun getQueueEntries(sessionId: ListeningSessionId): List<AdaptiveQueueEntry>

    /** @param limit capped at [MAX_QUERY_LIMIT] by implementations */
    fun getSignals(
        sessionId: ListeningSessionId,
        limit: Int,
    ): List<PlaybackSignal>

    /** @param limit capped at [MAX_QUERY_LIMIT] by implementations */
    fun getDecisions(
        sessionId: ListeningSessionId,
        limit: Int,
    ): List<LlmDecision>
}
