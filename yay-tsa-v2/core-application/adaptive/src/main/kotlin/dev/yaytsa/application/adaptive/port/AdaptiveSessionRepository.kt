package dev.yaytsa.application.adaptive.port

import dev.yaytsa.domain.adaptive.AdaptiveSessionAggregate
import dev.yaytsa.domain.adaptive.ListeningSessionId
import dev.yaytsa.shared.UserId

interface AdaptiveSessionRepository {
    fun find(sessionId: ListeningSessionId): AdaptiveSessionAggregate?

    fun findActiveByUser(userId: UserId): AdaptiveSessionAggregate?

    fun save(aggregate: AdaptiveSessionAggregate)
}
