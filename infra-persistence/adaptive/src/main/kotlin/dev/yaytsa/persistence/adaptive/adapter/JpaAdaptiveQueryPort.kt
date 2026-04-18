package dev.yaytsa.persistence.adaptive.adapter

import dev.yaytsa.application.adaptive.port.AdaptiveQueryPort
import dev.yaytsa.domain.adaptive.AdaptiveQueueEntry
import dev.yaytsa.domain.adaptive.ListeningSession
import dev.yaytsa.domain.adaptive.ListeningSessionId
import dev.yaytsa.domain.adaptive.LlmDecision
import dev.yaytsa.domain.adaptive.PlaybackSignal
import dev.yaytsa.persistence.adaptive.jpa.AdaptiveQueueEntryJpaRepository
import dev.yaytsa.persistence.adaptive.jpa.ListeningSessionJpaRepository
import dev.yaytsa.persistence.adaptive.jpa.LlmDecisionJpaRepository
import dev.yaytsa.persistence.adaptive.jpa.PlaybackSignalJpaRepository
import dev.yaytsa.persistence.adaptive.mapper.AdaptiveMappers
import dev.yaytsa.shared.UserId
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Repository
@Transactional(readOnly = true)
class JpaAdaptiveQueryPort(
    private val sessionJpa: ListeningSessionJpaRepository,
    private val queueJpa: AdaptiveQueueEntryJpaRepository,
    private val signalJpa: PlaybackSignalJpaRepository,
    private val decisionJpa: LlmDecisionJpaRepository,
) : AdaptiveQueryPort {
    override fun findAllActiveSessions(): List<ListeningSession> = sessionJpa.findByEndedAtIsNull().map { AdaptiveMappers.toDomain(it) }

    override fun findActiveSession(userId: UserId): ListeningSession? {
        val uid = UUID.fromString(userId.value)
        val entity = sessionJpa.findByUserIdAndEndedAtIsNull(uid) ?: return null
        return AdaptiveMappers.toDomain(entity)
    }

    override fun findSession(sessionId: ListeningSessionId): ListeningSession? {
        val id = UUID.fromString(sessionId.value)
        val entity = sessionJpa.findById(id).orElse(null) ?: return null
        return AdaptiveMappers.toDomain(entity)
    }

    override fun getQueueEntries(sessionId: ListeningSessionId): List<AdaptiveQueueEntry> {
        val id = UUID.fromString(sessionId.value)
        return queueJpa
            .findBySessionIdOrderByPositionAsc(id)
            .map { AdaptiveMappers.toDomain(it) }
    }

    override fun getSignals(
        sessionId: ListeningSessionId,
        limit: Int,
    ): List<PlaybackSignal> {
        val id = UUID.fromString(sessionId.value)
        return signalJpa
            .findBySessionIdOrderByCreatedAtDesc(id, PageRequest.of(0, limit.coerceIn(1, AdaptiveQueryPort.MAX_QUERY_LIMIT)))
            .map { AdaptiveMappers.toDomain(it) }
    }

    override fun getDecisions(
        sessionId: ListeningSessionId,
        limit: Int,
    ): List<LlmDecision> {
        val id = UUID.fromString(sessionId.value)
        return decisionJpa
            .findBySessionIdOrderByCreatedAtDesc(id, PageRequest.of(0, limit.coerceIn(1, AdaptiveQueryPort.MAX_QUERY_LIMIT)))
            .map { AdaptiveMappers.toDomain(it) }
    }
}
