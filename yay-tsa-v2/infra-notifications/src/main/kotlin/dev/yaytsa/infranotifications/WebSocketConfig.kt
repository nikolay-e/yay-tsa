package dev.yaytsa.infranotifications

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Configuration
import org.springframework.messaging.simp.config.ChannelRegistration
import org.springframework.messaging.simp.config.MessageBrokerRegistry
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker
import org.springframework.web.socket.config.annotation.StompEndpointRegistry
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer

@Configuration
@EnableWebSocketMessageBroker
class WebSocketConfig(
    private val authInterceptor: WebSocketAuthInterceptor,
    @Value("\${CORS_ORIGINS:http://localhost:*}") private val corsOrigins: String,
) : WebSocketMessageBrokerConfigurer {
    override fun configureMessageBroker(registry: MessageBrokerRegistry) {
        registry.enableSimpleBroker("/topic", "/queue")
        registry.setApplicationDestinationPrefixes("/app")
        registry.setUserDestinationPrefix("/user")
    }

    override fun registerStompEndpoints(registry: StompEndpointRegistry) {
        val allowedOriginPatterns =
            corsOrigins
                .split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toTypedArray()
        registry.addEndpoint("/ws").setAllowedOriginPatterns(*allowedOriginPatterns)
    }

    override fun configureClientInboundChannel(registration: ChannelRegistration) {
        registration.interceptors(authInterceptor)
    }
}
