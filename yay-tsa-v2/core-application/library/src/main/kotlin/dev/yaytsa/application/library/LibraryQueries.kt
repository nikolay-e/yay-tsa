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

    fun browseAlbumsExcludingGenres(
        excludedGenreNames: Collection<String>,
        limit: Int,
        offset: Int,
    ): List<Album> =
        if (excludedGenreNames.isEmpty()) {
            libraryQuery.browseAlbums(limit.coerceIn(1, MAX_PAGE_SIZE), offset.coerceAtLeast(0))
        } else {
            libraryQuery.browseAlbumsExcludingGenres(
                excludedGenreNames,
                limit.coerceIn(1, MAX_PAGE_SIZE),
                offset.coerceAtLeast(0),
            )
        }

    fun countAlbumsExcludingGenres(excludedGenreNames: Collection<String>): Int =
        if (excludedGenreNames.isEmpty()) libraryQuery.countAlbums() else libraryQuery.countAlbumsExcludingGenres(excludedGenreNames)

    fun browseArtistsExcludingGenres(
        excludedGenreNames: Collection<String>,
        limit: Int,
        offset: Int,
    ): List<Artist> =
        if (excludedGenreNames.isEmpty()) {
            libraryQuery.browseArtists(limit.coerceIn(1, MAX_PAGE_SIZE), offset.coerceAtLeast(0))
        } else {
            libraryQuery.browseArtistsExcludingGenres(
                excludedGenreNames,
                limit.coerceIn(1, MAX_PAGE_SIZE),
                offset.coerceAtLeast(0),
            )
        }

    fun countArtistsExcludingGenres(excludedGenreNames: Collection<String>): Int =
        if (excludedGenreNames.isEmpty()) libraryQuery.countArtists() else libraryQuery.countArtistsExcludingGenres(excludedGenreNames)

    fun browseAlbumsByCreatedDesc(
        limit: Int,
        offset: Int,
    ): List<Album> = libraryQuery.browseAlbumsByCreatedDesc(limit.coerceIn(1, MAX_PAGE_SIZE), offset.coerceAtLeast(0))

    fun browseAlbumsRandom(limit: Int): List<Album> = libraryQuery.browseAlbumsRandom(limit.coerceIn(1, MAX_PAGE_SIZE))

    fun browseAlbumsByYearRange(
        fromYear: Int,
        toYear: Int,
        limit: Int,
        offset: Int,
    ): List<Album> = libraryQuery.browseAlbumsByYearRange(fromYear, toYear, limit.coerceIn(1, MAX_PAGE_SIZE), offset.coerceAtLeast(0))

    fun browseAlbumsByGenre(
        genre: String,
        limit: Int,
        offset: Int,
    ): List<Album> = libraryQuery.browseAlbumsByGenre(genre, limit.coerceIn(1, MAX_PAGE_SIZE), offset.coerceAtLeast(0))

    fun browseAlbumsByArtist(artistId: EntityId): List<Album> = libraryQuery.browseAlbumsByArtist(artistId)

    fun browseTracksByAlbum(albumId: EntityId): List<Track> = libraryQuery.browseTracksByAlbum(albumId)

    fun browseTracksByArtist(
        artistId: EntityId,
        limit: Int,
        offset: Int,
    ): List<Track> = libraryQuery.browseTracksByArtist(artistId, limit, offset)

    fun countTracksByArtist(artistId: EntityId): Int = libraryQuery.countTracksByArtist(artistId)

    fun getTracksByIds(trackIds: List<EntityId>): List<Track> = if (trackIds.isEmpty()) emptyList() else libraryQuery.getTracksByIds(trackIds)

    fun browseTracksByGenreNames(genreNames: Collection<String>): List<Track> =
        if (genreNames.isEmpty()) emptyList() else libraryQuery.browseTracksByGenreNames(genreNames)

    fun browseTracks(
        limit: Int,
        offset: Int,
        sortBy: String,
        sortOrder: String,
    ): List<Track> = libraryQuery.browseTracks(limit.coerceIn(1, MAX_PAGE_SIZE), offset.coerceAtLeast(0), sortBy, sortOrder)

    fun browseTracksExcludingGenres(
        excludedGenreNames: Collection<String>,
        limit: Int,
        offset: Int,
        sortBy: String,
        sortOrder: String,
    ): List<Track> =
        if (excludedGenreNames.isEmpty()) {
            libraryQuery.browseTracks(limit.coerceIn(1, MAX_PAGE_SIZE), offset.coerceAtLeast(0), sortBy, sortOrder)
        } else {
            libraryQuery.browseTracksExcludingGenres(
                excludedGenreNames,
                limit.coerceIn(1, MAX_PAGE_SIZE),
                offset.coerceAtLeast(0),
                sortBy,
                sortOrder,
            )
        }

    fun countTracksExcludingGenres(excludedGenreNames: Collection<String>): Int =
        if (excludedGenreNames.isEmpty()) libraryQuery.countTracks() else libraryQuery.countTracksExcludingGenres(excludedGenreNames)

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

    fun browseArtistsGroupedByLetter(): Map<String, List<Artist>> = browseAllArtists().groupBy { indexLetter(it) }.toSortedMap()

    private fun browseAllArtists(): List<Artist> {
        val all = mutableListOf<Artist>()
        var offset = 0
        while (true) {
            val page = libraryQuery.browseArtists(MAX_PAGE_SIZE, offset)
            all += page
            if (page.size < MAX_PAGE_SIZE) return all
            offset += MAX_PAGE_SIZE
        }
    }

    private fun indexLetter(artist: Artist): String {
        val first = (artist.sortName ?: artist.name).trim().firstOrNull() ?: return "#"
        return if (first.isLetter()) first.uppercaseChar().toString() else "#"
    }
}
