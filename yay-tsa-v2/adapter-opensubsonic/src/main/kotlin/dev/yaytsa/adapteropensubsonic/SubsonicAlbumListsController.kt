package dev.yaytsa.adapteropensubsonic

import dev.yaytsa.application.library.LibraryQueries
import dev.yaytsa.application.preferences.PreferencesQueries
import dev.yaytsa.application.recommendation.PlayHistoryFunnelService
import dev.yaytsa.domain.library.Album
import dev.yaytsa.shared.EntityId
import dev.yaytsa.shared.UserId
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.security.Principal

@RestController
@RequestMapping("/rest")
class SubsonicAlbumListsController(
    private val libraryQueries: LibraryQueries,
    private val preferencesQueries: PreferencesQueries,
    private val playHistoryFunnel: PlayHistoryFunnelService,
    private val support: SubsonicEndpointSupport,
) {
    @GetMapping("/getAlbumList", "/getAlbumList.view")
    fun getAlbumList(
        @RequestParam type: String,
        @RequestParam(defaultValue = "10") size: Int,
        @RequestParam(defaultValue = "0") offset: Int,
        @RequestParam(required = false) fromYear: Int?,
        @RequestParam(required = false) toYear: Int?,
        @RequestParam(required = false) genre: String?,
        @RequestParam(required = false) f: String?,
        principal: Principal,
    ): ResponseEntity<String> {
        val albums = albumsForListType(type, size, offset, fromYear, toYear, genre, UserId(principal.name))
        val artistNames = libraryQueries.getEntityNamesByIds(albums.mapNotNull { it.artistId }.toSet())
        return support.write(
            ok {
                copy(
                    albumList =
                        AlbumListV1Wrapper(albums.map { support.toDirectoryChild(it, it.artistId?.let { aid -> artistNames[aid] }) }),
                )
            },
            f,
        )
    }

    @GetMapping("/getAlbumList2", "/getAlbumList2.view")
    fun getAlbumList2(
        @RequestParam type: String,
        @RequestParam(defaultValue = "10") size: Int,
        @RequestParam(defaultValue = "0") offset: Int,
        @RequestParam(required = false) fromYear: Int?,
        @RequestParam(required = false) toYear: Int?,
        @RequestParam(required = false) genre: String?,
        @RequestParam(required = false) f: String?,
        principal: Principal,
    ): ResponseEntity<String> {
        val albums = albumsForListType(type, size, offset, fromYear, toYear, genre, UserId(principal.name))
        return support.write(ok { copy(albumList2 = AlbumListWrapper(support.toAlbumElements(albums))) }, f)
    }

    private fun albumsForListType(
        type: String,
        size: Int,
        offset: Int,
        fromYear: Int?,
        toYear: Int?,
        genre: String?,
        userId: UserId,
    ): List<Album> =
        when (type.trim()) {
            "random" -> libraryQueries.browseAlbumsRandom(size)
            "newest" -> libraryQueries.browseAlbumsByCreatedDesc(size, offset)
            "recent" -> playHistoryFunnel.recentlyPlayedAlbums(userId, size, offset)
            "frequent" -> playHistoryFunnel.mostPlayedAlbums(userId, size, offset)
            "highest" -> mostFavoritedAlbums(userId, size, offset)
            "byYear" -> {
                if (fromYear == null || toYear == null) {
                    throw SubsonicApiException(10, "Required parameter is missing: fromYear/toYear")
                }
                libraryQueries.browseAlbumsByYearRange(fromYear, toYear, size, offset)
            }
            "byGenre" -> {
                if (genre.isNullOrBlank()) throw SubsonicApiException(10, "Required parameter is missing: genre")
                libraryQueries.browseAlbumsByGenre(genre, size, offset)
            }
            "starred" -> starredAlbums(userId, size, offset)
            else -> libraryQueries.browseAlbums(size, offset)
        }

    private fun mostFavoritedAlbums(
        userId: UserId,
        size: Int,
        offset: Int,
    ): List<Album> {
        val favorites = preferencesQueries.find(userId)?.favorites.orEmpty()
        if (favorites.isEmpty()) return libraryQueries.browseAlbumsByCreatedDesc(size, offset)
        val albumIds =
            libraryQueries
                .getTracksByIds(favorites.map { EntityId(it.trackId.value) })
                .mapNotNull { it.albumId }
                .groupingBy { it }
                .eachCount()
                .entries
                .sortedWith(compareByDescending<Map.Entry<EntityId, Int>> { it.value }.thenBy { it.key.value })
                .map { it.key }
        return libraryQueries.getAlbumsByIds(pageOf(albumIds, size, offset))
    }

    private fun pageOf(
        albumIds: List<EntityId>,
        size: Int,
        offset: Int,
    ): List<EntityId> =
        albumIds
            .drop(offset.coerceAtLeast(0))
            .take(size.coerceIn(1, LibraryQueries.MAX_PAGE_SIZE))

    // Subsonic "starred" albums are derived from favorited tracks: an album is starred when it
    // holds at least one favorited track (the favorites model is per-track, there is no album star).
    private fun starredAlbums(
        userId: UserId,
        size: Int,
        offset: Int,
    ): List<Album> {
        val favorites = preferencesQueries.find(userId)?.favorites.orEmpty()
        if (favorites.isEmpty()) return emptyList()
        val favoritedTracks = libraryQueries.getTracksByIds(favorites.map { EntityId(it.trackId.value) })
        val albumIds = favoritedTracks.mapNotNull { it.albumId }.distinct()
        return libraryQueries.getAlbumsByIds(pageOf(albumIds, size, offset))
    }
}
