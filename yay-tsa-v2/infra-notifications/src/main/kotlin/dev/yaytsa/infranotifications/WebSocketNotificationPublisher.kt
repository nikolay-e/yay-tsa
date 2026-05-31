package dev.yaytsa.infranotifications

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.stereotype.Component

@Component
@org.springframework.context.annotation.Primary
class WebSocketNotificationPublisher(
    private val messagingTemplate: SimpMessagingTemplate,
    private val objectMapper: ObjectMapper,
) : NotificationPublisher {
    override fun publish(
        context: String,
        payload: String,
    ) {
        val targetUserId = extractUserId(payload)
        if (targetUserId != null) {
            messagingTemplate.convertAndSendToUser(targetUserId, "/queue/$context", payload)
        } else {
            messagingTemplate.convertAndSend("/topic/$context", payload)
        }
    }

    private fun extractUserId(payload: String): String? =
        runCatching {
            objectMapper
                .readTree(payload)
                .get("userId")
                ?.takeIf { it.isTextual }
                ?.asText()
        }.getOrNull()
}
