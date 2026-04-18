package dev.yaytsa.application.library.port

import dev.yaytsa.domain.library.Album
import dev.yaytsa.domain.library.Artist
import dev.yaytsa.domain.library.Genre
import dev.yaytsa.domain.library.Image
import dev.yaytsa.domain.library.SearchResults
import dev.yaytsa.domain.library.Track
import dev.yaytsa.shared.EntityId
import dev.yaytsa.shared.TrackId

interface LibraryQueryPort {
    fun getTrack(trackId: EntityId): Track?

    fun getAlbum(albumId: EntityId): Album?

    fun getArtist(artistId: EntityId): Artist?

    fun browseArtists(
        limit: Int,
        offset: Int,
    ): List<Artist>

    fun browseAlbums(
        limit: Int,
        offset: Int,
    ): List<Album>

    fun browseAlbumsByArtist(artistId: EntityId): List<Album>

    fun browseTracksByAlbum(albumId: EntityId): List<Track>

    fun searchText(
        query: String,
        limit: Int,
        offset: Int,
    ): SearchResults

    fun trackIdsExist(trackIds: Set<TrackId>): Set<TrackId>

    fun getGenres(entityId: EntityId): List<Genre>

    fun getPrimaryImage(entityId: EntityId): Image?

    fun resolveTrackFilePath(trackId: EntityId): String?
}
