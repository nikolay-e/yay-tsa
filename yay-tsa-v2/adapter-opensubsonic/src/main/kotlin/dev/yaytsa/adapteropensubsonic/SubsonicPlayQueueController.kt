package dev.yaytsa.adapteropensubsonic

import dev.yaytsa.application.auth.AuthQueries
import dev.yaytsa.application.library.LibraryQueries
import dev.yaytsa.application.playback.DeviceSessionProjection
import dev.yaytsa.application.playback.PlaybackQueries
import dev.yaytsa.application.playback.SavedPlayQueueService
import dev.yaytsa.application.shared.port.Clock
import dev.yaytsa.shared.EntityId
import dev.yaytsa.shared.UserId
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.security.Principal

@RestController
@RequestMapping("/rest")
class SubsonicPlayQueueController(
    private val authQueries: AuthQueries,
    private val libraryQueries: LibraryQueries,
    private val savedPlayQueueService: SavedPlayQueueService,
    private val deviceSessionProjection: DeviceSessionProjection,
    private val playbackQueries: PlaybackQueries,
    private val clock: Clock,
    private val support: SubsonicEndpointSupport,
) {
    @GetMapping("/getNowPlaying", "/getNowPlaying.view")
    fun getNowPlaying(
        @RequestParam(required = false) f: String?,
        principal: Principal,
    ): ResponseEntity<String> {
        val userId = UserId(principal.name)
        val currentTrackIds =
            deviceSessionProjection
                .getByUser(userId)
                .mapNotNull { session ->
                    val state = playbackQueries.getPlaybackState(userId, session.sessionId) ?: return@mapNotNull null
                    val entry = state.currentEntryId ?: return@mapNotNull null
                    state.queue.firstOrNull { it.id == entry }?.trackId
                }.distinct()
        val tracks = libraryQueries.getTracksByIds(currentTrackIds.map { EntityId(it.value) })
        return support.write(ok { copy(nowPlaying = NowPlayingWrapper(entry = support.toChildren(tracks))) }, f)
    }

    @GetMapping("/savePlayQueue", "/savePlayQueue.view")
    fun savePlayQueue(
        @RequestParam(required = false) id: List<String>?,
        @RequestParam(required = false) current: String?,
        @RequestParam(required = false) position: Long?,
        @RequestParam(required = false) c: String?,
        @RequestParam(required = false) f: String?,
        principal: Principal,
    ): ResponseEntity<String> {
        val userId = UserId(principal.name)
        savedPlayQueueService.save(
            userId = userId,
            trackIds = id.orEmpty(),
            currentTrackId = current,
            positionMs = position ?: 0,
            changedBy = c,
            requestTime = clock.now(),
        )
        return support.write(ok(), f)
    }

    @GetMapping("/getPlayQueue", "/getPlayQueue.view")
    fun getPlayQueue(
        @RequestParam(required = false) f: String?,
        principal: Principal,
    ): ResponseEntity<String> {
        val userId = UserId(principal.name)
        val saved =
            savedPlayQueueService.find(userId)
                ?: return support.write(ok(), f)
        if (saved.trackIds.isEmpty()) return support.write(ok(), f)
        val username = authQueries.findUser(userId)?.username
        val entries = support.toChildren(libraryQueries.getTracksByIds(saved.trackIds.mapNotNull { support.safeEntityId(it) }))
        return support.write(
            ok {
                copy(
                    playQueue =
                        PlayQueueWrapper(
                            current = saved.currentTrackId,
                            position = saved.positionMs,
                            changed = saved.changedAt.toString(),
                            changedBy = saved.changedBy,
                            username = username,
                            entry = entries,
                        ),
                )
            },
            f,
        )
    }
}
