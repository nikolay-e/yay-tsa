package dev.yaytsa.infranotifications

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.stereotype.Component

@Component
@ConditionalOnMissingBean(SimpMessagingTemplate::class)
class LoggingNotificationPublisher : NotificationPublisher {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun publish(
        context: String,
        payload: String,
    ) {
        log.info("Notification [{}]: {}", context, payload)
    }
}
