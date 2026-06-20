package dev.yaytsa.adapterjellyfin

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Per-user SSE fan-out for /v1/me/devices/events. Mirrors GroupEventBroadcaster.
 * A user's tabs/devices each open an EventSource and register here; the
 * DeviceSseNotificationBridge forwards playback-state notifications as
 * `device_state_changed` events so a second device's now-playing updates live,
 * not only on the 15s heartbeat poll. In-memory => single-replica (the device
 * projection is already in-memory and single-replica, so this matches).
 */
@Component
class DeviceEventBroadcaster {
    private val log = LoggerFactory.getLogger(javaClass)
    private val subscribers = ConcurrentHashMap<String, CopyOnWriteArrayList<SseEmitter>>()

    fun subscribe(userId: String): SseEmitter {
        val emitter = SseEmitter(SSE_TIMEOUT_MS)
        val list = subscribers.computeIfAbsent(userId) { CopyOnWriteArrayList() }
        list.add(emitter)
        emitter.onCompletion { list.remove(emitter) }
        emitter.onTimeout {
            list.remove(emitter)
            emitter.complete()
        }
        runCatching { emitter.send(SseEmitter.event().name("ready").data(mapOf("ts" to System.currentTimeMillis()))) }
        return emitter
    }

    fun emit(
        userId: String,
        eventName: String,
        payload: Any,
    ) {
        val list = subscribers[userId] ?: return
        list.forEach { emitter ->
            try {
                emitter.send(SseEmitter.event().name(eventName).data(payload))
            } catch (e: Exception) {
                log.debug("Dropping dead device SSE subscriber for user {}: {}", userId, e.message)
                list.remove(emitter)
            }
        }
    }

    companion object {
        private const val SSE_TIMEOUT_MS = 30 * 60 * 1000L
    }
}
