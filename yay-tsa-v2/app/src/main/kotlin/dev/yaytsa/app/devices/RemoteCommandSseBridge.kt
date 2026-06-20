package dev.yaytsa.app.devices

import com.fasterxml.jackson.databind.ObjectMapper
import dev.yaytsa.adapterjellyfin.DeviceEventBroadcaster
import dev.yaytsa.infranotifications.BestEffortNotificationPublisher
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class RemoteCommandSseBridge(
    private val deviceEventBroadcaster: DeviceEventBroadcaster,
    private val objectMapper: ObjectMapper,
) : BestEffortNotificationPublisher {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun publish(
        context: String,
        payload: String,
    ) {
        if (context != DEVICE_COMMAND_CONTEXT) return
        val node = runCatching { objectMapper.readTree(payload) }.getOrNull() ?: return
        val userId = node.get("userId")?.takeIf { it.isTextual }?.asText() ?: return
        val targetDeviceId = node.get("targetDeviceId")?.takeIf { it.isTextual }?.asText() ?: return
        val command = node.get("command")?.takeIf { it.isTextual }?.asText() ?: return
        val params =
            node.get("params")?.let {
                runCatching { objectMapper.convertValue(it, Map::class.java) }.getOrNull()
            } ?: emptyMap<String, Any?>()
        val commandId = node.get("commandId")?.takeIf { it.isTextual }?.asText()
        deviceEventBroadcaster.emit(
            userId,
            COMMAND_EVENT,
            buildMap {
                put("targetDeviceId", targetDeviceId)
                put("type", command)
                put("payload", params)
                commandId?.let { put("commandId", it) }
            },
        )
        log.debug("Forwarded remote command {} to user {} device {}", command, userId, targetDeviceId)
    }

    private companion object {
        const val DEVICE_COMMAND_CONTEXT = "device-command"
        const val COMMAND_EVENT = "command"
    }
}
