package dev.yaytsa.persistence.playback.adapter

import dev.yaytsa.application.playback.port.PlaybackSessionRepository
import dev.yaytsa.application.shared.port.OptimisticLockException
import dev.yaytsa.domain.playback.PlaybackSessionAggregate
import dev.yaytsa.domain.playback.SessionId
import dev.yaytsa.persistence.playback.entity.PlaybackSessionEntityId
import dev.yaytsa.persistence.playback.jpa.PlaybackSessionJpaRepository
import dev.yaytsa.persistence.playback.jpa.QueueEntryJpaRepository
import dev.yaytsa.persistence.playback.mapper.toDomain
import dev.yaytsa.persistence.playback.mapper.toEntity
import dev.yaytsa.persistence.playback.mapper.toQueueEntryEntities
import dev.yaytsa.shared.UserId
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

@Repository
@Transactional
class JpaPlaybackSessionRepository(
    private val sessionJpa: PlaybackSessionJpaRepository,
    private val queueEntryJpa: QueueEntryJpaRepository,
    private val entityManager: EntityManager,
) : PlaybackSessionRepository {
    @Transactional(readOnly = true)
    override fun find(
        userId: UserId,
        sessionId: SessionId,
    ): PlaybackSessionAggregate? {
        val id = PlaybackSessionEntityId(userId.value, sessionId.value)
        val entity = sessionJpa.findById(id).orElse(null) ?: return null
        val queueEntries = queueEntryJpa.findByUserIdAndSessionIdOrderByPosition(userId.value, sessionId.value)
        return toDomain(entity, queueEntries)
    }

    override fun save(aggregate: PlaybackSessionAggregate) {
        val entity = aggregate.toEntity()
        val isNew = !sessionJpa.existsById(PlaybackSessionEntityId(entity.userId, entity.sessionId))

        if (isNew) {
            entityManager.persist(entity)
        } else {
            val updatedRows =
                entityManager
                    .createQuery(
                        """
                        UPDATE PlaybackSessionEntity s SET
                            s.currentEntryId = :currentEntryId,
                            s.playbackState = :playbackState,
                            s.lastKnownPositionMs = :lastKnownPositionMs,
                            s.lastKnownAt = :lastKnownAt,
                            s.leaseOwner = :leaseOwner,
                            s.leaseExpiresAt = :leaseExpiresAt,
                            s.version = :nextVersion
                        WHERE s.userId = :userId AND s.sessionId = :sessionId AND s.version = :expectedVersion
                        """.trimIndent(),
                    ).setParameter("currentEntryId", entity.currentEntryId)
                    .setParameter("playbackState", entity.playbackState)
                    .setParameter("lastKnownPositionMs", entity.lastKnownPositionMs)
                    .setParameter("lastKnownAt", entity.lastKnownAt)
                    .setParameter("leaseOwner", entity.leaseOwner)
                    .setParameter("leaseExpiresAt", entity.leaseExpiresAt)
                    .setParameter("nextVersion", entity.version)
                    .setParameter("userId", entity.userId)
                    .setParameter("sessionId", entity.sessionId)
                    .setParameter("expectedVersion", aggregate.version.value - 1)
                    .executeUpdate()

            if (updatedRows == 0) {
                throw OptimisticLockException(
                    "PlaybackSession ${aggregate.userId.value}/${aggregate.sessionId.value} was modified concurrently (expected version ${aggregate.version.value - 1})",
                )
            }

            entityManager.clear()
        }

        // Replace queue entries: delete existing, flush, then insert new ones
        queueEntryJpa.deleteByUserIdAndSessionId(aggregate.userId.value, aggregate.sessionId.value)
        queueEntryJpa.flush()
        queueEntryJpa.saveAll(aggregate.toQueueEntryEntities())
    }
}
