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

    fun getEntityNamesByIds(ids: Set<EntityId>): Map<EntityId, String>

    fun browseArtists(
        limit: Int,
        offset: Int,
    ): List<Artist>

    fun browseAlbums(
        limit: Int,
        offset: Int,
    ): List<Album>

    fun browseAlbumsByCreatedDesc(
        limit: Int,
        offset: Int,
    ): List<Album>

    fun browseAlbumsRandom(limit: Int): List<Album>

    fun browseAlbumsByYearRange(
        fromYear: Int,
        toYear: Int,
        limit: Int,
        offset: Int,
    ): List<Album>

    fun browseAlbumsByGenre(
        genre: String,
        limit: Int,
        offset: Int,
    ): List<Album>

    fun browseAlbumsByArtist(artistId: EntityId): List<Album>

    fun browseTracksByAlbum(albumId: EntityId): List<Track>

    fun browseTracksByArtist(
        artistId: EntityId,
        limit: Int,
        offset: Int,
    ): List<Track>

    fun countTracksByArtist(artistId: EntityId): Int

    fun getTracksByIds(trackIds: List<EntityId>): List<Track>

    fun browseTracksByGenreNames(genreNames: Collection<String>): List<Track>

    fun browseTracks(
        limit: Int,
        offset: Int,
        sortBy: String,
        sortOrder: String,
    ): List<Track>

    fun browseTracksRandom(limit: Int): List<Track>

    fun searchText(
        query: String,
        limit: Int,
        offset: Int,
    ): SearchResults

    fun trackIdsExist(trackIds: Set<TrackId>): Set<TrackId>

    fun getGenres(entityId: EntityId): List<Genre>

    fun getPrimaryImage(entityId: EntityId): Image?

    fun resolveTrackFilePath(trackId: EntityId): String?

    fun countTracks(): Int

    fun countAlbums(): Int

    fun countArtists(): Int

    fun countTextSearchTracks(query: String): Int

    fun countTextSearchArtists(query: String): Int

    fun countTextSearchAlbums(query: String): Int

    fun countAlbumsByArtistIds(artistIds: Set<EntityId>): Map<EntityId, Int>
}
