package dev.yaytsa.adapteropensubsonic

import dev.yaytsa.adaptershared.ChildElement
import dev.yaytsa.application.library.LibraryQueries
import dev.yaytsa.application.ml.port.MlQueryPort
import dev.yaytsa.application.playback.port.PlayHistoryQueryPort
import dev.yaytsa.application.recommendation.MusicSurfaceFilter
import dev.yaytsa.shared.EntityId
import dev.yaytsa.shared.TrackId
import dev.yaytsa.shared.UserId
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.security.Principal

@RestController
@RequestMapping("/rest")
class SubsonicSongListsController(
    private val libraryQueries: LibraryQueries,
    private val mlQueries: MlQueryPort,
    private val playHistoryQueries: PlayHistoryQueryPort,
    private val musicSurfaceFilter: MusicSurfaceFilter,
    private val support: SubsonicEndpointSupport,
) {
    private companion object {
        const val TOP_SONGS_CANDIDATE_LIMIT = 1000
    }

    @GetMapping("/getSongsByGenre", "/getSongsByGenre.view")
    fun getSongsByGenre(
        @RequestParam genre: String,
        @RequestParam(defaultValue = "10") count: Int,
        @RequestParam(defaultValue = "0") offset: Int,
        @RequestParam(required = false) f: String?,
    ): ResponseEntity<String> {
        val tracks = libraryQueries.browseTracksByGenre(genre, count, offset)
        return support.write(ok { copy(songsByGenre = SongsWrapper(support.toChildren(tracks))) }, f)
    }

    @GetMapping("/getRandomSongs", "/getRandomSongs.view")
    fun getRandomSongs(
        @RequestParam(defaultValue = "10") size: Int,
        @RequestParam(required = false) genre: String?,
        @RequestParam(required = false) fromYear: Int?,
        @RequestParam(required = false) toYear: Int?,
        @RequestParam(required = false) f: String?,
    ): ResponseEntity<String> {
        val tracks = libraryQueries.browseTracksRandomFiltered(genre?.takeIf { it.isNotBlank() }, fromYear, toYear, size)
        return support.write(ok { copy(randomSongs = SongsWrapper(support.toChildren(tracks))) }, f)
    }

    @GetMapping("/getSimilarSongs", "/getSimilarSongs.view")
    fun getSimilarSongs(
        @RequestParam id: String,
        @RequestParam(defaultValue = "50") count: Int,
        @RequestParam(required = false) f: String?,
        principal: Principal,
    ): ResponseEntity<String> {
        val children = similarSongChildren(id, count, UserId(principal.name)) ?: return support.notFound("Item", id, f)
        return support.write(ok { copy(similarSongs = SongsWrapper(children)) }, f)
    }

    @GetMapping("/getSimilarSongs2", "/getSimilarSongs2.view")
    fun getSimilarSongs2(
        @RequestParam id: String,
        @RequestParam(defaultValue = "50") count: Int,
        @RequestParam(required = false) f: String?,
        principal: Principal,
    ): ResponseEntity<String> {
        val children = similarSongChildren(id, count, UserId(principal.name)) ?: return support.notFound("Item", id, f)
        return support.write(ok { copy(similarSongs2 = SongsWrapper(children)) }, f)
    }

    private fun similarSongChildren(
        id: String,
        count: Int,
        userId: UserId,
    ): List<ChildElement>? {
        val entityId = support.safeEntityId(id) ?: return null
        val seedTrackId =
            when {
                libraryQueries.getTrack(entityId) != null -> entityId
                libraryQueries.getAlbum(entityId) != null ->
                    libraryQueries.browseTracksByAlbum(entityId).firstOrNull()?.id ?: return emptyList()
                libraryQueries.getArtist(entityId) != null ->
                    libraryQueries.browseTracksByArtist(entityId, 1, 0).firstOrNull()?.id ?: return emptyList()
                else -> return null
            }
        val similarIds = mlQueries.findSimilarTracks(TrackId(seedTrackId.value), count.coerceIn(1, LibraryQueries.MAX_PAGE_SIZE))
        if (similarIds.isEmpty()) return emptyList()
        val similarTracks = libraryQueries.getTracksByIds(similarIds.map { EntityId(it.value) })
        return support.toChildren(musicSurfaceFilter.filter(similarTracks, userId))
    }

    @GetMapping("/getTopSongs", "/getTopSongs.view")
    fun getTopSongs(
        @RequestParam artist: String,
        @RequestParam(defaultValue = "50") count: Int,
        @RequestParam(required = false) f: String?,
        principal: Principal,
    ): ResponseEntity<String> {
        val artistEntity = libraryQueries.findArtistByName(artist) ?: return support.notFound("Artist", artist, f)
        val artistTracks =
            musicSurfaceFilter.filter(
                libraryQueries.browseTracksByArtist(artistEntity.id, TOP_SONGS_CANDIDATE_LIMIT, 0),
                UserId(principal.name),
            )
        val playCounts = playHistoryQueries.playCountsByTrackIds(artistTracks.map { TrackId(it.id.value) })
        val ranked = artistTracks.sortedByDescending { playCounts[TrackId(it.id.value)] ?: 0L }
        return support.write(
            ok { copy(topSongs = SongsWrapper(support.toChildren(ranked.take(count.coerceIn(1, LibraryQueries.MAX_PAGE_SIZE))))) },
            f,
        )
    }
}
