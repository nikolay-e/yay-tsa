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
    companion object {
        const val MAX_PAGE_SIZE = 200
    }

    fun getTrack(trackId: EntityId): Track? = libraryQuery.getTrack(trackId)

    fun getAlbum(albumId: EntityId): Album? = libraryQuery.getAlbum(albumId)

    fun getArtist(artistId: EntityId): Artist? = libraryQuery.getArtist(artistId)

    fun getEntityNamesByIds(ids: Set<EntityId>): Map<EntityId, String> = if (ids.isEmpty()) emptyMap() else libraryQuery.getEntityNamesByIds(ids)

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

    fun browseTracksByArtist(
        artistId: EntityId,
        limit: Int,
        offset: Int,
    ): List<Track> = libraryQuery.browseTracksByArtist(artistId, limit, offset)

    fun countTracksByArtist(artistId: EntityId): Int = libraryQuery.countTracksByArtist(artistId)

    fun getTracksByIds(trackIds: List<EntityId>): List<Track> = if (trackIds.isEmpty()) emptyList() else libraryQuery.getTracksByIds(trackIds)

    fun browseTracks(
        limit: Int,
        offset: Int,
        sortBy: String,
        sortOrder: String,
    ): List<Track> = libraryQuery.browseTracks(limit.coerceIn(1, MAX_PAGE_SIZE), offset.coerceAtLeast(0), sortBy, sortOrder)

    fun browseTracksRandom(limit: Int): List<Track> = libraryQuery.browseTracksRandom(limit)

    fun searchText(
        query: String,
        limit: Int,
        offset: Int,
    ): SearchResults = libraryQuery.searchText(query, limit.coerceIn(1, MAX_PAGE_SIZE), offset.coerceAtLeast(0))

    fun trackIdsExist(trackIds: Set<TrackId>): Set<TrackId> = libraryQuery.trackIdsExist(trackIds)

    fun getGenres(entityId: EntityId): List<Genre> = libraryQuery.getGenres(entityId)

    fun getPrimaryImage(entityId: EntityId): Image? = libraryQuery.getPrimaryImage(entityId)

    fun resolveTrackFilePath(trackId: EntityId): String? = libraryQuery.resolveTrackFilePath(trackId)

    fun countTracks(): Int = libraryQuery.countTracks()

    fun countAlbums(): Int = libraryQuery.countAlbums()

    fun countArtists(): Int = libraryQuery.countArtists()

    fun countTextSearchTracks(query: String): Int = libraryQuery.countTextSearchTracks(query)

    fun countTextSearchArtists(query: String): Int = libraryQuery.countTextSearchArtists(query)

    fun countTextSearchAlbums(query: String): Int = libraryQuery.countTextSearchAlbums(query)

    fun countAlbumsByArtistIds(artistIds: Set<EntityId>): Map<EntityId, Int> =
        if (artistIds.isEmpty()) emptyMap() else libraryQuery.countAlbumsByArtistIds(artistIds)

    fun browseArtistsGroupedByLetter(): Map<String, List<Artist>> =
        libraryQuery
            .browseArtists(500, 0)
            .groupBy { (it.sortName ?: it.name).first().uppercaseChar().toString() }
            .toSortedMap()
}
