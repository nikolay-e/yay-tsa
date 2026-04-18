package dev.yaytsa.infranotifications

interface NotificationPublisher {
    fun publish(
        context: String,
        payload: String,
    )
}
