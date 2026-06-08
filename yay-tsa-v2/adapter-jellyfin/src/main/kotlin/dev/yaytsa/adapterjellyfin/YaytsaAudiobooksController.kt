package dev.yaytsa.adapterjellyfin

import dev.yaytsa.adaptershared.BaseItem
import dev.yaytsa.adaptershared.TrackLookups
import dev.yaytsa.adaptershared.UserItemData
import dev.yaytsa.adaptershared.msToTicks
import dev.yaytsa.adaptershared.toJellyfinBaseItem
import dev.yaytsa.application.library.LibraryQueries
import dev.yaytsa.application.playback.ResumePosition
import dev.yaytsa.application.playback.ResumePositionService
import dev.yaytsa.application.playback.ResumeStatus
import dev.yaytsa.application.preferences.PreferencesQueries
import dev.yaytsa.domain.library.Track
import dev.yaytsa.application.shared.port.Clock
import dev.yaytsa.shared.EntityId
import dev.yaytsa.shared.UserId
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.security.Principal

@RestController
@RequestMapping("/v1/me/audiobooks")
class YaytsaAudiobooksController(
    private val libraryQueries: LibraryQueries,
    private val preferencesQueries: PreferencesQueries,
    private val resumePositionService: ResumePositionService,
    private val clock: Clock,
) {
    data class ResumeDto(
        val positionMs: Long,
        val runTimeMs: Long,
        val status: String,
        val progressPercent: Int,
        val remainingMs: Long,
        val updatedAt: String,
    )

    data class AudiobookDto(
        val item: BaseItem,
        val resume: ResumeDto,
    )

    @GetMapping
    fun list(principal: Principal): ResponseEntity<List<AudiobookDto>> {
        val uid = UserId(principal.name)
        val rows = resumePositionService.findAll(uid)
        if (rows.isEmpty()) return ResponseEntity.ok(emptyList())

        val favTrackIds =
            (preferencesQueries.find(uid)?.favorites ?: emptyList())
                .map { it.trackId.value }
                .toSet()

        val tracksById =
            libraryQueries
                .getTracksByIds(rows.map { EntityId(it.itemId) })
                .filter { isAudiobook(it) }
                .associateBy { it.id.value }

        val lookups = trackLookups(tracksById.values.toList())

        val dtos =
            rows
                .mapNotNull { resume ->
                    val track = tracksById[resume.itemId] ?: return@mapNotNull null
                    AudiobookDto(
                        item = buildItem(track, favTrackIds, lookups, resume),
                        resume = resume.toDto(),
                    )
                }.sortedByDescending { it.resume.updatedAt }

        return ResponseEntity.ok(dtos)
    }

    @PostMapping("/{itemId}/finished")
    fun markFinished(
        @PathVariable itemId: String,
        principal: Principal,
    ): ResponseEntity<ResumeDto> {
        val resume = resumePositionService.markFinished(UserId(principal.name), itemId, clock.now()) ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(resume.toDto())
    }

    @PostMapping("/{itemId}/restart")
    fun restart(
        @PathVariable itemId: String,
        principal: Principal,
    ): ResponseEntity<ResumeDto> {
        val resume = resumePositionService.restart(UserId(principal.name), itemId, clock.now())
        return ResponseEntity.ok(resume.toDto())
    }

    private fun buildItem(
        track: Track,
        favTrackIds: Set<String>,
        lookups: TrackLookups,
        resume: ResumePosition,
    ): BaseItem {
        val base = track.toJellyfinBaseItem(favTrackIds, lookups)
        val finished = resume.status == ResumeStatus.FINISHED
        return base.copy(
            userData =
                (base.userData ?: UserItemData()).copy(
                    playbackPositionTicks = if (finished) 0L else (msToTicks(resume.positionMs) ?: 0L),
                    played = finished,
                ),
        )
    }

    private fun ResumePosition.toDto(): ResumeDto {
        val remaining = (runTimeMs - positionMs).coerceAtLeast(0)
        return ResumeDto(
            positionMs = positionMs,
            runTimeMs = runTimeMs,
            status = status.wireValue(),
            progressPercent = (progressFraction * 100).toInt(),
            remainingMs = remaining,
            updatedAt = updatedAt.toString(),
        )
    }

    private fun trackLookups(tracks: List<Track>): TrackLookups {
        val albumIds = tracks.mapNotNull { it.albumId }.toSet()
        val artistIds = tracks.mapNotNull { it.albumArtistId }.toSet()
        return TrackLookups(
            albumNames = libraryQueries.getEntityNamesByIds(albumIds),
            artistNames = libraryQueries.getEntityNamesByIds(artistIds),
        )
    }

    companion object {
        private val AUDIOBOOK_GENRES = setOf("audiobook", "audiobooks")

        fun isAudiobook(track: Track): Boolean = track.genre?.trim()?.lowercase() in AUDIOBOOK_GENRES
    }
}
