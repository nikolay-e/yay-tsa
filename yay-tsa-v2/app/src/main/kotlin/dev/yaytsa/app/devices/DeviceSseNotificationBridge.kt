package dev.yaytsa.app.devices

import com.fasterxml.jackson.databind.ObjectMapper
import dev.yaytsa.adapterjellyfin.DeviceEventBroadcaster
import dev.yaytsa.adapterjellyfin.DeviceNowPlayingResolver
import dev.yaytsa.domain.playback.SessionId
import dev.yaytsa.infranotifications.BestEffortNotificationPublisher
import dev.yaytsa.shared.UserId
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Bridges the outbox notification stream to the per-user device SSE channel.
 * On a "playback" notification (carrying userId + sessionId) it resolves the
 * now-playing view from the authoritative aggregate and pushes a
 * `device_state_changed` event to that user's /v1/me/devices/events subscribers,
 * so a second device updates live rather than only on the 15s heartbeat poll.
 *
 * Registered as one of several NotificationPublishers consumed by
 * OutboxEntryProcessor; it acts only on the "playback" context and is a no-op
 * for every other notification, so it never disturbs the WebSocket fan-out.
 */
@Component
class DeviceSseNotificationBridge(
    private val deviceEventBroadcaster: DeviceEventBroadcaster,
    private val nowPlayingResolver: DeviceNowPlayingResolver,
    private val objectMapper: ObjectMapper,
) : BestEffortNotificationPublisher {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun publish(
        context: String,
        payload: String,
    ) {
        if (context != PLAYBACK_CONTEXT) return
        val node = runCatching { objectMapper.readTree(payload) }.getOrNull() ?: return
        val userId = node.get("userId")?.takeIf { it.isTextual }?.asText() ?: return
        val sessionId = node.get("sessionId")?.takeIf { it.isTextual }?.asText() ?: return
        val nowPlaying = runCatching { nowPlayingResolver.resolve(UserId(userId), SessionId(sessionId)) }.getOrNull() ?: return
        // Field shape matches the PWA's DeviceStateEvent (deviceId + isPaused). When a
        // controlling device owns the session, useDeviceEvents patches that device's
        // now-playing in place. On a lease release controllingDeviceId is null: the PWA
        // can't patch (no deviceId matches) and falls through to a full device-list
        // refetch — the ONLY live signal that clears the releasing device's card, since
        // the 15s heartbeat is POST-only and never re-reads the list. So still emit;
        // dropping the event here strands the observer's card on the stale track.
        deviceEventBroadcaster.emit(
            userId,
            DEVICE_STATE_CHANGED,
            mapOf(
                "sessionId" to sessionId,
                "deviceId" to nowPlaying.controllingDeviceId,
                "nowPlayingItemId" to nowPlaying.nowPlayingItemId,
                "nowPlayingItemName" to nowPlaying.nowPlayingItemName,
                "positionMs" to nowPlaying.positionMs,
                "isPaused" to (nowPlaying.playbackState != "PLAYING"),
            ),
        )
        log.debug("Forwarded device_state_changed to user {} for session {}", userId, sessionId)
    }

    private companion object {
        const val PLAYBACK_CONTEXT = "playback"
        const val DEVICE_STATE_CHANGED = "device_state_changed"
    }
}
