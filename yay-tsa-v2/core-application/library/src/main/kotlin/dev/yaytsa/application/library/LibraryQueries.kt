package dev.yaytsa.application.library

import dev.yaytsa.application.library.port.LibraryQueryPort
import dev.yaytsa.domain.library.Album
import dev.yaytsa.domain.library.Artist
import dev.yaytsa.domain.library.Genre
import dev.yaytsa.domain.library.Image
import dev.yaytsa.domain.library.SearchResults
import dev.yaytsa.domain.library.Track
import dev.yaytsa.shared.EntityId
import dev.yaytsa.shared.TrackId

class LibraryQueries(
    private val libraryQuery: LibraryQueryPort,
) {
    fun getTrack(trackId: EntityId): Track? = libraryQuery.getTrack(trackId)

    fun getAlbum(albumId: EntityId): Album? = libraryQuery.getAlbum(albumId)

    fun getArtist(artistId: EntityId): Artist? = libraryQuery.getArtist(artistId)

    fun browseArtists(
        limit: Int,
        offset: Int,
    ): List<Artist> = libraryQuery.browseArtists(limit, offset)

    fun browseAlbums(
        limit: Int,
        offset: Int,
    ): List<Album> = libraryQuery.browseAlbums(limit, offset)

    fun browseAlbumsByArtist(artistId: EntityId): List<Album> = libraryQuery.browseAlbumsByArtist(artistId)

    fun browseTracksByAlbum(albumId: EntityId): List<Track> = libraryQuery.browseTracksByAlbum(albumId)

    fun searchText(
        query: String,
        limit: Int,
        offset: Int,
    ): SearchResults = libraryQuery.searchText(query, limit, offset)

    fun trackIdsExist(trackIds: Set<TrackId>): Set<TrackId> = libraryQuery.trackIdsExist(trackIds)

    fun getGenres(entityId: EntityId): List<Genre> = libraryQuery.getGenres(entityId)

    fun getPrimaryImage(entityId: EntityId): Image? = libraryQuery.getPrimaryImage(entityId)

    fun resolveTrackFilePath(trackId: EntityId): String? = libraryQuery.resolveTrackFilePath(trackId)

    fun browseArtistsGroupedByLetter(): Map<String, List<Artist>> =
        libraryQuery
            .browseArtists(500, 0)
            .groupBy { (it.sortName ?: it.name).first().uppercaseChar().toString() }
            .toSortedMap()
}
