package dev.yaytsa.adapterjellyfin

import dev.yaytsa.application.library.LibraryQueries
import dev.yaytsa.application.playback.PlaybackUseCases
import dev.yaytsa.application.shared.port.Clock
import dev.yaytsa.domain.playback.SessionId
import dev.yaytsa.shared.EntityId
import dev.yaytsa.shared.UserId
import org.springframework.stereotype.Component

data class DeviceNowPlaying(
    val controllingDeviceId: String?,
    val nowPlayingItemId: String?,
    val nowPlayingItemName: String?,
    val positionMs: Long,
    val playbackState: String,
)

/**
 * Resolves the per-device "now playing" view from the authoritative playback
 * aggregate: current entry -> track id -> library name, plus lazily-computed
 * position and the controlling (lease-owning) device. Shared by the device-list
 * endpoint and the SSE bridge so both surface identical data.
 */
@Component
class DeviceNowPlayingResolver(
    private val playbackUseCases: PlaybackUseCases,
    private val libraryQueries: LibraryQueries,
    private val clock: Clock,
) {
    fun resolve(
        userId: UserId,
        sessionId: SessionId,
    ): DeviceNowPlaying {
        val state =
            playbackUseCases.getPlaybackState(userId, sessionId)
                ?: return DeviceNowPlaying(null, null, null, 0L, "STOPPED")
        val currentTrackId = state.currentEntryId?.let { entry -> state.queue.firstOrNull { it.id == entry }?.trackId }
        val name = currentTrackId?.let { libraryQueries.getTrack(EntityId(it.value))?.name }
        return DeviceNowPlaying(
            controllingDeviceId = state.lease?.owner?.value,
            nowPlayingItemId = currentTrackId?.value,
            nowPlayingItemName = name,
            positionMs = state.computePosition(clock.now()).toMillis(),
            playbackState = state.playbackState.name,
        )
    }
}
