package dev.yaytsa.persistence.adaptive.adapter

import dev.yaytsa.application.adaptive.port.AdaptiveSessionRepository
import dev.yaytsa.application.shared.port.OptimisticLockException
import dev.yaytsa.domain.adaptive.AdaptiveQueueEntryData
import dev.yaytsa.domain.adaptive.AdaptiveQueueEntryId
import dev.yaytsa.domain.adaptive.AdaptiveSessionAggregate
import dev.yaytsa.domain.adaptive.ListeningSessionId
import dev.yaytsa.domain.adaptive.SessionState
import dev.yaytsa.persistence.adaptive.entity.AdaptiveQueueEntryEntity
import dev.yaytsa.persistence.adaptive.entity.ListeningSessionEntity
import dev.yaytsa.persistence.adaptive.jpa.AdaptiveQueueEntryJpaRepository
import dev.yaytsa.persistence.adaptive.jpa.ListeningSessionJpaRepository
import dev.yaytsa.shared.AggregateVersion
import dev.yaytsa.shared.EntityId
import dev.yaytsa.shared.TrackId
import dev.yaytsa.shared.UserId
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Repository
@Transactional
class JpaAdaptiveSessionRepository(
    private val sessionJpa: ListeningSessionJpaRepository,
    private val queueEntryJpa: AdaptiveQueueEntryJpaRepository,
    private val entityManager: EntityManager,
) : AdaptiveSessionRepository {
    @Transactional(readOnly = true)
    override fun find(sessionId: ListeningSessionId): AdaptiveSessionAggregate? {
        val uuid = UUID.fromString(sessionId.value)
        val entity = sessionJpa.findById(uuid).orElse(null) ?: return null
        val queueEntries = queueEntryJpa.findBySessionIdOrderByPositionAsc(uuid)
        return toDomain(entity, queueEntries)
    }

    @Transactional(readOnly = true)
    override fun findActiveByUser(userId: UserId): AdaptiveSessionAggregate? {
        val uuid = UUID.fromString(userId.value)
        val entity = sessionJpa.findByUserIdAndEndedAtIsNull(uuid) ?: return null
        val queueEntries = queueEntryJpa.findBySessionIdOrderByPositionAsc(entity.id)
        return toDomain(entity, queueEntries)
    }

    override fun save(aggregate: AdaptiveSessionAggregate) {
        val entity = toEntity(aggregate)
        val isNew = !sessionJpa.existsById(entity.id)

        if (isNew) {
            entityManager.persist(entity)
        } else {
            val updatedRows =
                entityManager
                    .createQuery(
                        """
                        UPDATE ListeningSessionEntity s SET
                            s.state = :state,
                            s.lastActivityAt = :lastActivityAt,
                            s.endedAt = :endedAt,
                            s.sessionSummary = :sessionSummary,
                            s.energy = :energy,
                            s.intensity = :intensity,
                            s.moodTags = :moodTags,
                            s.attentionMode = :attentionMode,
                            s.seedTrackId = :seedTrackId,
                            s.seedGenres = :seedGenres,
                            s.version = :nextVersion
                        WHERE s.id = :id AND s.version = :expectedVersion
                        """.trimIndent(),
                    ).setParameter("state", entity.state)
                    .setParameter("lastActivityAt", entity.lastActivityAt)
                    .setParameter("endedAt", entity.endedAt)
                    .setParameter("sessionSummary", entity.sessionSummary)
                    .setParameter("energy", entity.energy)
                    .setParameter("intensity", entity.intensity)
                    .setParameter("moodTags", entity.moodTags)
                    .setParameter("attentionMode", entity.attentionMode)
                    .setParameter("seedTrackId", entity.seedTrackId)
                    .setParameter("seedGenres", entity.seedGenres)
                    .setParameter("nextVersion", entity.version)
                    .setParameter("id", entity.id)
                    .setParameter("expectedVersion", aggregate.version.value - 1)
                    .executeUpdate()

            if (updatedRows == 0) {
                throw OptimisticLockException(
                    "AdaptiveSession ${aggregate.id.value} was modified concurrently (expected version ${aggregate.version.value - 1})",
                )
            }

            entityManager.clear()
        }

        // Replace queue entries: delete existing, flush, then insert new ones
        queueEntryJpa.deleteBySessionId(UUID.fromString(aggregate.id.value))
        queueEntryJpa.flush()
        queueEntryJpa.saveAll(toQueueEntryEntities(aggregate))
    }

    private fun toDomain(
        entity: ListeningSessionEntity,
        queueEntries: List<AdaptiveQueueEntryEntity>,
    ): AdaptiveSessionAggregate {
        val maxQueueVersion = queueEntries.maxOfOrNull { it.queueVersion } ?: 0L
        return AdaptiveSessionAggregate(
            id = ListeningSessionId(entity.id.toString()),
            userId = UserId(entity.userId.toString()),
            state = entity.state?.let { SessionState.valueOf(it) } ?: SessionState.ACTIVE,
            startedAt = entity.startedAt,
            lastActivityAt = entity.lastActivityAt,
            endedAt = entity.endedAt,
            sessionSummary = entity.sessionSummary,
            energy = entity.energy,
            intensity = entity.intensity,
            moodTags = entity.moodTags,
            attentionMode = entity.attentionMode,
            seedTrackId = entity.seedTrackId?.let { EntityId(it.toString()) },
            seedGenres = entity.seedGenres,
            queue =
                queueEntries.map { e ->
                    AdaptiveQueueEntryData(
                        id = AdaptiveQueueEntryId(e.id.toString()),
                        trackId = TrackId(e.trackId.toString()),
                        position = e.position,
                        addedReason = e.addedReason,
                        intentLabel = e.intentLabel,
                        queueVersion = e.queueVersion,
                        addedAt = e.addedAt,
                    )
                },
            queueVersion = maxQueueVersion,
            version = AggregateVersion(entity.version),
        )
    }

    private fun toEntity(aggregate: AdaptiveSessionAggregate): ListeningSessionEntity =
        ListeningSessionEntity(
            id = UUID.fromString(aggregate.id.value),
            userId = UUID.fromString(aggregate.userId.value),
            state = aggregate.state.name,
            startedAt = aggregate.startedAt,
            lastActivityAt = aggregate.lastActivityAt,
            endedAt = aggregate.endedAt,
            sessionSummary = aggregate.sessionSummary,
            energy = aggregate.energy,
            intensity = aggregate.intensity,
            moodTags = aggregate.moodTags,
            attentionMode = aggregate.attentionMode,
            seedTrackId = aggregate.seedTrackId?.let { UUID.fromString(it.value) },
            seedGenres = aggregate.seedGenres,
            version = aggregate.version.value,
        )

    private fun toQueueEntryEntities(aggregate: AdaptiveSessionAggregate): List<AdaptiveQueueEntryEntity> =
        aggregate.queue.map { entry ->
            AdaptiveQueueEntryEntity(
                id = UUID.fromString(entry.id.value),
                sessionId = UUID.fromString(aggregate.id.value),
                trackId = UUID.fromString(entry.trackId.value),
                position = entry.position,
                addedReason = entry.addedReason,
                intentLabel = entry.intentLabel,
                queueVersion = entry.queueVersion,
                addedAt = entry.addedAt,
            )
        }
}
