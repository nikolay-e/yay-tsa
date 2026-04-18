package dev.yaytsa.infranotifications

import dev.yaytsa.application.auth.AuthQueries
import dev.yaytsa.shared.UserId
import org.springframework.messaging.Message
import org.springframework.messaging.MessageChannel
import org.springframework.messaging.simp.stomp.StompCommand
import org.springframework.messaging.simp.stomp.StompHeaderAccessor
import org.springframework.messaging.support.ChannelInterceptor
import org.springframework.messaging.support.MessageHeaderAccessor
import org.springframework.stereotype.Component

@Component
class WebSocketAuthInterceptor(
    private val authQueries: AuthQueries,
) : ChannelInterceptor {
    override fun preSend(
        message: Message<*>,
        channel: MessageChannel,
    ): Message<*> {
        val accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor::class.java)
        if (accessor?.command == StompCommand.CONNECT) {
            val token =
                accessor.getFirstNativeHeader("X-Emby-Token")
                    ?: accessor.getFirstNativeHeader("Authorization")?.removePrefix("Bearer ")
            if (token != null) {
                val user = authQueries.findByApiToken(token)
                if (user != null && user.isActive) {
                    accessor.user = WsPrincipal(user.id)
                }
            }
            if (accessor.user == null) {
                throw org.springframework.messaging.MessageDeliveryException("Authentication required")
            }
        }
        return message
    }
}

class WsPrincipal(
    val userId: UserId,
) : java.security.Principal {
    override fun getName(): String = userId.value
}
