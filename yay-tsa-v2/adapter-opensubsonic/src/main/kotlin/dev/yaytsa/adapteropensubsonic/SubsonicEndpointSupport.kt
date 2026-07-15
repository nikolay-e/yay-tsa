package dev.yaytsa.adapteropensubsonic

import dev.yaytsa.adaptershared.ChildElement
import dev.yaytsa.adaptershared.SubsonicFailureTranslator
import dev.yaytsa.adaptershared.TrackLookups
import dev.yaytsa.adaptershared.toSubsonicChild
import dev.yaytsa.application.library.LibraryQueries
import dev.yaytsa.domain.library.Album
import dev.yaytsa.domain.library.Track
import dev.yaytsa.shared.CommandResult
import dev.yaytsa.shared.EntityId
import dev.yaytsa.shared.Failure
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class SubsonicEndpointSupport(
    private val libraryQueries: LibraryQueries,
    private val responseWriter: SubsonicResponseWriter,
    private val failureTranslator: SubsonicFailureTranslator,
) {
    fun write(
        response: SubsonicResponse,
        f: String?,
    ): ResponseEntity<String> = responseWriter.write(response, f)

    fun errorFrom(failure: Failure): SubsonicResponse {
        val payload = failureTranslator.translate(failure)
        return error(payload.code, payload.message)
    }

    fun responseFor(result: CommandResult<*>): SubsonicResponse =
        when (result) {
            is CommandResult.Success -> ok()
            is CommandResult.Failed -> errorFrom(result.failure)
        }

    fun failWith(failure: Failure): Nothing {
        val payload = failureTranslator.translate(failure)
        throw SubsonicApiException(payload.code, payload.message)
    }

    fun safeEntityId(value: String): EntityId? = runCatching { UUID.fromString(value) }.getOrNull()?.let { EntityId(value) }

    fun notFound(
        entityType: String,
        id: String,
        f: String?,
    ): ResponseEntity<String> = write(errorFrom(Failure.NotFound(entityType, id)), f)

    fun toChild(track: Track): ChildElement = track.toSubsonicChild(TrackLookups.load(listOf(track), libraryQueries))

    fun toChildren(tracks: List<Track>): List<ChildElement> {
        val lookups = TrackLookups.load(tracks, libraryQueries)
        return tracks.map { it.toSubsonicChild(lookups) }
    }

    fun toAlbumElements(albums: List<Album>): List<AlbumElement> {
        val artistNames = libraryQueries.getEntityNamesByIds(albums.mapNotNull { it.artistId }.toSet())
        return albums.map { album ->
            AlbumElement(
                id = album.id.value,
                name = album.name,
                artist = album.artistId?.let { artistNames[it] },
                artistId = album.artistId?.value,
                year = album.releaseDate?.year,
                songCount = album.totalTracks,
                coverArt = album.coverImagePath?.let { album.id.value },
            )
        }
    }

    fun toDirectoryChild(
        album: Album,
        artistName: String?,
    ): ChildElement =
        ChildElement(
            id = album.id.value,
            parent = album.artistId?.value,
            title = album.name,
            artist = artistName,
            artistId = album.artistId?.value,
            year = album.releaseDate?.year,
            coverArt = album.coverImagePath?.let { album.id.value },
            isDir = true,
        )
}
