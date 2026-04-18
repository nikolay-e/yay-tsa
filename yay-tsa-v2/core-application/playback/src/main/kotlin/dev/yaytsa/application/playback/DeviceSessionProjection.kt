package dev.yaytsa.application.playback

import dev.yaytsa.domain.playback.DeviceId
import dev.yaytsa.domain.playback.SessionId
import dev.yaytsa.shared.UserId
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

data class DeviceSession(
    val userId: UserId,
    val sessionId: SessionId,
    val deviceId: DeviceId,
    val lastSeenAt: Instant,
)

class DeviceSessionProjection {
    private val sessions = ConcurrentHashMap<DeviceId, DeviceSession>()

    fun register(
        userId: UserId,
        sessionId: SessionId,
        deviceId: DeviceId,
        now: Instant,
    ) {
        sessions[deviceId] = DeviceSession(userId, sessionId, deviceId, now)
    }

    fun heartbeat(
        deviceId: DeviceId,
        now: Instant,
    ) {
        sessions.computeIfPresent(deviceId) { _, s -> s.copy(lastSeenAt = now) }
    }

    fun remove(deviceId: DeviceId) {
        sessions.remove(deviceId)
    }

    fun getByUser(userId: UserId): List<DeviceSession> = sessions.values.filter { it.userId == userId }

    fun getAll(): Collection<DeviceSession> = sessions.values

    fun evictStale(cutoff: Instant) {
        sessions.entries.removeIf { it.value.lastSeenAt.isBefore(cutoff) }
    }
}
