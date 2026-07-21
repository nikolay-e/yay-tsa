package dev.yaytsa.application.library

import dev.yaytsa.application.library.port.GenreStatistics
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

        // CD filler / hidden-track padding (e.g. dozens of 10s silent "Blank"/"Silent" rips) is
        // worthless as a suggestion; real interludes in the library start above this bound.
        const val MIN_RANDOM_TRACK_DURATION_MS = 15_000L
    }

    fun getTrack(trackId: EntityId): Track? = libraryQuery.getTrack(trackId)

    fun getAlbum(albumId: EntityId): Album? = libraryQuery.getAlbum(albumId)

    fun getArtist(artistId: EntityId): Artist? = libraryQuery.getArtist(artistId)

    fun getEntityNamesByIds(ids: Set<EntityId>): Map<EntityId, String> = if (ids.isEmpty()) emptyMap() else libraryQuery.getEntityNamesByIds(ids)

    fun browseArtists(
        limit: Int,
        offset: Int,
        sortBy: String = "SortName",
        sortOrder: String = "Ascending",
    ): List<Artist> = libraryQuery.browseArtists(limit.coerceIn(1, MAX_PAGE_SIZE), offset.coerceAtLeast(0), sortBy, sortOrder)

    fun browseAlbums(
        limit: Int,
        offset: Int,
        sortBy: String = "SortName",
        sortOrder: String = "Ascending",
    ): List<Album> = libraryQuery.browseAlbums(limit.coerceIn(1, MAX_PAGE_SIZE), offset.coerceAtLeast(0), sortBy, sortOrder)

    fun browseAlbumsExcludingGenres(
        excludedGenreNames: Collection<String>,
        limit: Int,
        offset: Int,
        sortBy: String = "SortName",
        sortOrder: String = "Ascending",
    ): List<Album> =
        if (excludedGenreNames.isEmpty()) {
            libraryQuery.browseAlbums(limit.coerceIn(1, MAX_PAGE_SIZE), offset.coerceAtLeast(0), sortBy, sortOrder)
        } else {
            libraryQuery.browseAlbumsExcludingGenres(
                excludedGenreNames,
                limit.coerceIn(1, MAX_PAGE_SIZE),
                offset.coerceAtLeast(0),
                sortBy,
                sortOrder,
            )
        }

    fun countAlbumsExcludingGenres(excludedGenreNames: Collection<String>): Int =
        if (excludedGenreNames.isEmpty()) libraryQuery.countAlbums() else libraryQuery.countAlbumsExcludingGenres(excludedGenreNames)

    fun browseArtistsExcludingGenres(
        excludedGenreNames: Collection<String>,
        limit: Int,
        offset: Int,
        sortBy: String = "SortName",
        sortOrder: String = "Ascending",
    ): List<Artist> =
        if (excludedGenreNames.isEmpty()) {
            libraryQuery.browseArtists(limit.coerceIn(1, MAX_PAGE_SIZE), offset.coerceAtLeast(0), sortBy, sortOrder)
        } else {
            libraryQuery.browseArtistsExcludingGenres(
                excludedGenreNames,
                limit.coerceIn(1, MAX_PAGE_SIZE),
                offset.coerceAtLeast(0),
                sortBy,
                sortOrder,
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

    fun getAlbumsByIds(albumIds: List<EntityId>): List<Album> = if (albumIds.isEmpty()) emptyList() else libraryQuery.getAlbumsByIds(albumIds)

    fun getArtistsByIds(artistIds: List<EntityId>): List<Artist> = if (artistIds.isEmpty()) emptyList() else libraryQuery.getArtistsByIds(artistIds)

    fun findArtistByName(name: String): Artist? = libraryQuery.findArtistByName(name)

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

    fun browseTracksRandom(limit: Int): List<Track> =
        libraryQuery
            .browseTracksRandom(limit * 2)
            .filter { (it.durationMs ?: Long.MAX_VALUE) >= MIN_RANDOM_TRACK_DURATION_MS }
            .take(limit)

    fun browseTracksRandomFiltered(
        genre: String?,
        fromYear: Int?,
        toYear: Int?,
        limit: Int,
    ): List<Track> = libraryQuery.browseTracksRandomFiltered(genre, fromYear, toYear, limit.coerceIn(1, MAX_PAGE_SIZE))

    fun browseTracksByGenre(
        genre: String,
        limit: Int,
        offset: Int,
    ): List<Track> = libraryQuery.browseTracksByGenre(genre, limit.coerceIn(1, MAX_PAGE_SIZE), offset.coerceAtLeast(0))

    fun listGenreStatistics(): List<GenreStatistics> = libraryQuery.listGenreStatistics()

    // Strip NUL bytes before any user search term reaches SQL. pg_trgm/ILIKE binds the term as a
    // parameter, and PostgreSQL rejects 0x00 with SQLState 22021 ("invalid byte sequence for encoding
    // UTF8") — a fuzzed or pasted NUL then throws a DB exception (logged at ERROR by Hibernate) even
    // though the client correctly gets a 400. Scrubbing at this single application-layer entry covers
    // every protocol adapter (Jellyfin, Subsonic, MCP, Adaptive) so the query runs cleanly instead.
    private fun scrubQuery(query: String): String = query.filter { it.code != 0 }

    fun searchText(
        query: String,
        limit: Int,
        offset: Int,
        excludedGenres: Collection<String> = emptyList(),
    ): SearchResults =
        libraryQuery.searchText(
            scrubQuery(query),
            limit.coerceIn(1, MAX_PAGE_SIZE),
            offset.coerceAtLeast(0),
            excludedGenres.map { it.lowercase() },
        )

    fun trackIdsExist(trackIds: Set<TrackId>): Set<TrackId> = libraryQuery.trackIdsExist(trackIds)

    fun filterTrackIdsExcludingGenres(
        trackIds: Set<TrackId>,
        excludedGenreNames: Collection<String>,
    ): Set<TrackId> =
        if (excludedGenreNames.isEmpty()) {
            libraryQuery.trackIdsExist(trackIds)
        } else {
            libraryQuery.filterTrackIdsExcludingGenres(trackIds, excludedGenreNames)
        }

    fun getGenres(entityId: EntityId): List<Genre> = libraryQuery.getGenres(entityId)

    fun getPrimaryImage(entityId: EntityId): Image? = libraryQuery.getPrimaryImage(entityId)

    fun resolveTrackFilePath(trackId: EntityId): String? = libraryQuery.resolveTrackFilePath(trackId)

    fun countTracks(): Int = libraryQuery.countTracks()

    fun sumTrackDurationsMs(): Long = libraryQuery.sumTrackDurationsMs()

    fun countAlbums(): Int = libraryQuery.countAlbums()

    fun countArtists(): Int = libraryQuery.countArtists()

    fun countTextSearchTracks(query: String): Int = libraryQuery.countTextSearchTracks(scrubQuery(query))

    fun countTextSearchArtists(query: String): Int = libraryQuery.countTextSearchArtists(scrubQuery(query))

    fun countTextSearchAlbums(query: String): Int = libraryQuery.countTextSearchAlbums(scrubQuery(query))

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
