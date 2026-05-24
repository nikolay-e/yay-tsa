package dev.yaytsa.app.groups

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

@Component
class GroupEventBroadcaster {
    private val log = LoggerFactory.getLogger(javaClass)
    private val subscribers = ConcurrentHashMap<UUID, CopyOnWriteArrayList<SseEmitter>>()

    fun subscribe(groupId: UUID): SseEmitter {
        val emitter = SseEmitter(SSE_TIMEOUT_MS)
        val list = subscribers.computeIfAbsent(groupId) { CopyOnWriteArrayList() }
        list.add(emitter)
        emitter.onCompletion { list.remove(emitter) }
        emitter.onTimeout {
            list.remove(emitter)
            emitter.complete()
        }
        runCatching { emitter.send(SseEmitter.event().name("ready").data("{}")) }
        return emitter
    }

    fun emit(
        groupId: UUID,
        eventName: String,
        payload: Any,
    ) {
        val list = subscribers[groupId] ?: return
        list.forEach { emitter ->
            try {
                emitter.send(SseEmitter.event().name(eventName).data(payload))
            } catch (e: Exception) {
                log.debug("Dropping dead SSE subscriber for group {}: {}", groupId, e.message)
                list.remove(emitter)
            }
        }
    }

    companion object {
        private const val SSE_TIMEOUT_MS = 30 * 60 * 1000L
    }
}
