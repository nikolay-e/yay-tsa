package dev.yaytsa.infranotifications

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class LoggingNotificationPublisher : BestEffortNotificationPublisher {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun publish(
        context: String,
        payload: String,
    ) {
        log.debug("Notification [{}]: {}", context, payload)
    }
}
