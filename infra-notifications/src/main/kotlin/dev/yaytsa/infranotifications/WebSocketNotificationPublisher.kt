package dev.yaytsa.infranotifications

import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.stereotype.Component

@Component
@org.springframework.context.annotation.Primary
class WebSocketNotificationPublisher(
    private val messagingTemplate: SimpMessagingTemplate,
) : NotificationPublisher {
    override fun publish(
        context: String,
        payload: String,
    ) {
        messagingTemplate.convertAndSend("/topic/$context", payload)
    }
}
