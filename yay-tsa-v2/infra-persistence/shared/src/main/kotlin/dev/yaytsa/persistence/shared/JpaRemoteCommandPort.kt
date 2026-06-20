package dev.yaytsa.persistence.shared

import dev.yaytsa.application.shared.port.DomainNotification
import dev.yaytsa.application.shared.port.OutboxPort
import dev.yaytsa.application.shared.port.RemoteCommandPort
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
class JpaRemoteCommandPort(
    private val outboxPort: OutboxPort,
) : RemoteCommandPort {
    @Transactional
    override fun publish(
        userId: String,
        targetDeviceId: String,
        command: String,
        params: Map<String, Any?>,
    ) {
        outboxPort.enqueue(DomainNotification.RemoteCommand(userId, targetDeviceId, command, params))
    }
}
