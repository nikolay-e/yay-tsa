package dev.yaytsa.infranotifications

interface NotificationPublisher {
    fun publish(
        context: String,
        payload: String,
    )
}

/**
 * A best-effort, non-authoritative notification channel (e.g. a derived SSE fan-out).
 * The outbox processor isolates its failures so they never block marking the outbox
 * entry published — but an authoritative publisher's failure MUST still roll the
 * entry back so the poller retries (the transactional-outbox at-least-once guarantee).
 */
interface BestEffortNotificationPublisher : NotificationPublisher
