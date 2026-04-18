package dev.yaytsa.persistence.shared

import com.fasterxml.jackson.databind.ObjectMapper
import dev.yaytsa.application.shared.port.DomainNotification
import dev.yaytsa.application.shared.port.OutboxPort
import dev.yaytsa.persistence.shared.entity.OutboxEntity
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Repository

@Repository
class JpaOutboxPort(
    private val entityManager: EntityManager,
    private val clock: dev.yaytsa.application.shared.port.Clock,
    private val objectMapper: ObjectMapper,
) : OutboxPort {
    override fun enqueue(notification: DomainNotification) {
        val payloadMap =
            when (notification) {
                is DomainNotification.PlaybackStateChanged ->
                    mapOf("userId" to notification.userId, "sessionId" to notification.sessionId)
                is DomainNotification.PlaylistChanged ->
                    mapOf("playlistId" to notification.playlistId)
                is DomainNotification.PreferencesChanged ->
                    mapOf("userId" to notification.userId)
                is DomainNotification.LibraryChanged ->
                    mapOf("entityId" to notification.entityId)
                is DomainNotification.AuthChanged ->
                    mapOf("userId" to notification.userId)
                is DomainNotification.AdaptiveQueueChanged ->
                    mapOf("sessionId" to notification.sessionId)
            }
        val entity =
            OutboxEntity(
                context = notification.context,
                payload = objectMapper.writeValueAsString(payloadMap),
                createdAt = clock.now(),
            )
        entityManager.persist(entity)
    }
}
