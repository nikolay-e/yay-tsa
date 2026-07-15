package dev.yaytsa.adapteropensubsonic

import dev.yaytsa.adaptershared.AdapterCommandContextFactory
import dev.yaytsa.adaptershared.ChildElement
import dev.yaytsa.application.library.LibraryQueries
import dev.yaytsa.application.playback.ScrobbleService
import dev.yaytsa.application.preferences.PreferencesQueries
import dev.yaytsa.application.preferences.PreferencesUseCases
import dev.yaytsa.application.shared.port.Clock
import dev.yaytsa.domain.preferences.SetFavorite
import dev.yaytsa.domain.preferences.UnsetFavorite
import dev.yaytsa.shared.AggregateVersion
import dev.yaytsa.shared.CommandContext
import dev.yaytsa.shared.CommandResult
import dev.yaytsa.shared.EntityId
import dev.yaytsa.shared.Failure
import dev.yaytsa.shared.TrackId
import dev.yaytsa.shared.UserId
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.security.Principal
import java.time.Instant

@RestController
@RequestMapping("/rest")
class SubsonicAnnotationController(
    private val libraryQueries: LibraryQueries,
    private val preferencesQueries: PreferencesQueries,
    private val preferencesUseCases: PreferencesUseCases,
    private val scrobbleService: ScrobbleService,
    private val clock: Clock,
    @Qualifier("subsonicCommandContextFactory")
    private val ctxFactory: AdapterCommandContextFactory,
    private val support: SubsonicEndpointSupport,
) {
    private companion object {
        const val SOURCE_SUBSONIC = "subsonic"
        const val MISSING_ID_MESSAGE = "Required parameter is missing: id"
    }

    @GetMapping("/star", "/star.view")
    fun star(
        @RequestParam(required = false) id: List<String>?,
        @RequestParam(required = false) albumId: List<String>?,
        @RequestParam(required = false) artistId: List<String>?,
        @RequestParam(required = false) f: String?,
        principal: Principal,
    ): ResponseEntity<String> {
        val userId = validateFavoriteTargets(id, albumId, artistId, principal)
        val requested =
            id
                .orEmpty()
                .mapNotNull { support.safeEntityId(it)?.value }
                .map { TrackId(it) }
                .toSet()
        val knownTracks = if (requested.isEmpty()) emptySet() else libraryQueries.trackIdsExist(requested)
        if (knownTracks.isEmpty() && !anyResolvesToAlbumOrArtist(requested)) {
            return support.notFound("Song", id.orEmpty().joinToString(), f)
        }
        for (trackId in knownTracks) {
            val ctx = preferencesContext(userId)
            val result = preferencesUseCases.execute(SetFavorite(userId, trackId, ctx.requestTime), ctx)
            if (result is CommandResult.Failed) return support.write(support.errorFrom(result.failure), f)
        }
        return support.write(ok(), f)
    }

    private fun anyResolvesToAlbumOrArtist(ids: Set<TrackId>): Boolean {
        val entityIds = ids.map { EntityId(it.value) }
        return libraryQueries.getAlbumsByIds(entityIds).isNotEmpty() ||
            libraryQueries.getArtistsByIds(entityIds).isNotEmpty()
    }

    private fun validateFavoriteTargets(
        id: List<String>?,
        albumId: List<String>?,
        artistId: List<String>?,
        principal: Principal,
    ): UserId {
        if (id.isNullOrEmpty() && albumId.isNullOrEmpty() && artistId.isNullOrEmpty()) {
            throw SubsonicApiException(10, MISSING_ID_MESSAGE)
        }
        if (!albumId.isNullOrEmpty() || !artistId.isNullOrEmpty()) {
            throw SubsonicApiException(0, "Starring albums or artists is not supported; favorites are per-song")
        }
        return UserId(principal.name)
    }

    private fun preferencesContext(userId: UserId): CommandContext {
        val prefs = preferencesQueries.find(userId)
        return ctxFactory.create(userId, prefs?.version ?: AggregateVersion.INITIAL)
    }

    @GetMapping("/unstar", "/unstar.view")
    fun unstar(
        @RequestParam(required = false) id: List<String>?,
        @RequestParam(required = false) albumId: List<String>?,
        @RequestParam(required = false) artistId: List<String>?,
        @RequestParam(required = false) f: String?,
        principal: Principal,
    ): ResponseEntity<String> {
        val userId = validateFavoriteTargets(id, albumId, artistId, principal)
        for (rawId in id.orEmpty()) {
            val entityId = support.safeEntityId(rawId) ?: continue
            val ctx = preferencesContext(userId)
            val result = preferencesUseCases.execute(UnsetFavorite(userId, TrackId(entityId.value)), ctx)
            if (result is CommandResult.Failed && result.failure !is Failure.NotFound) {
                return support.write(support.errorFrom(result.failure), f)
            }
        }
        return support.write(ok(), f)
    }

    private fun favoriteChildren(userId: UserId): List<ChildElement> {
        val favorites =
            preferencesQueries
                .find(userId)
                ?.favorites
                .orEmpty()
                .sortedBy { it.position }
        if (favorites.isEmpty()) return emptyList()
        val favoritedAtByTrack = favorites.associate { it.trackId.value to it.favoritedAt }
        return support
            .toChildren(libraryQueries.getTracksByIds(favorites.map { EntityId(it.trackId.value) }))
            .map { child -> child.copy(starred = favoritedAtByTrack[child.id]?.toString()) }
    }

    @GetMapping("/getStarred", "/getStarred.view")
    fun getStarred(
        @RequestParam(required = false) f: String?,
        principal: Principal,
    ): ResponseEntity<String> = support.write(ok { copy(starred = StarredWrapper(song = favoriteChildren(UserId(principal.name)))) }, f)

    @GetMapping("/getStarred2", "/getStarred2.view")
    fun getStarred2(
        @RequestParam(required = false) f: String?,
        principal: Principal,
    ): ResponseEntity<String> = support.write(ok { copy(starred2 = Starred2(song = favoriteChildren(UserId(principal.name)))) }, f)

    @GetMapping("/scrobble", "/scrobble.view")
    fun scrobble(
        @RequestParam(required = false) id: List<String>?,
        @RequestParam(required = false) time: List<Long>?,
        @RequestParam(defaultValue = "true") submission: Boolean,
        @RequestParam(required = false) f: String?,
        principal: Principal,
    ): ResponseEntity<String> {
        if (id.isNullOrEmpty()) throw SubsonicApiException(10, MISSING_ID_MESSAGE)
        if (!submission) return support.write(ok(), f)
        val userId = UserId(principal.name)
        id.forEachIndexed { index, rawId ->
            val entityId = support.safeEntityId(rawId) ?: return@forEachIndexed
            val track = libraryQueries.getTrack(entityId) ?: return@forEachIndexed
            val durationMs = track.durationMs ?: 0L
            val startedAt = time?.getOrNull(index)?.let { Instant.ofEpochMilli(it) } ?: clock.now().minusMillis(durationMs)
            scrobbleService.recordScrobble(
                userId = userId,
                trackId = TrackId(entityId.value),
                startedAt = startedAt,
                stoppedAt = startedAt.plusMillis(durationMs),
                positionMs = durationMs,
                runTimeMs = durationMs,
                source = SOURCE_SUBSONIC,
            )
        }
        return support.write(ok(), f)
    }
}
