package dev.yaytsa.adapterjellyfin

import dev.yaytsa.application.library.LibraryQueries
import dev.yaytsa.application.playlists.PlaylistQueries
import dev.yaytsa.application.preferences.PreferencesQueries
import dev.yaytsa.domain.library.Album
import dev.yaytsa.domain.library.Artist
import dev.yaytsa.domain.library.Track
import dev.yaytsa.shared.EntityId
import dev.yaytsa.shared.UserId
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.security.Principal

@RestController
class JellyfinItemsController(
    private val libraryQueries: LibraryQueries,
    private val preferencesQueries: PreferencesQueries,
    private val playlistQueries: PlaylistQueries,
) {
    @GetMapping("/Items")
    fun getItems(
        @RequestParam(required = false) userId: String?,
        @RequestParam(name = "ParentId", required = false) parentId: String?,
        @RequestParam(name = "IncludeItemTypes", required = false) includeItemTypes: String?,
        @RequestParam(name = "Recursive", required = false) recursive: Boolean?,
        @RequestParam(name = "SortBy", required = false) sortBy: String?,
        @RequestParam(name = "SortOrder", required = false) sortOrder: String?,
        @RequestParam(name = "StartIndex", required = false, defaultValue = "0") startIndex: Int,
        @RequestParam(name = "Limit", required = false, defaultValue = "50") limit: Int,
        @RequestParam(name = "SearchTerm", required = false) searchTerm: String?,
        @RequestParam(name = "ArtistIds", required = false) artistIds: String?,
        @RequestParam(name = "AlbumIds", required = false) albumIds: String?,
        @RequestParam(name = "IsFavorite", required = false) isFavorite: Boolean?,
        @RequestParam(name = "Ids", required = false) ids: String?,
        principal: Principal?,
    ): ResponseEntity<Any> {
        val uid = userId ?: principal?.name
        val favTrackIds =
            if (uid != null) {
                (preferencesQueries.find(UserId(uid))?.favorites ?: emptyList())
                    .map { it.trackId.value }
                    .toSet()
            } else {
                emptySet()
            }

        // Handle specific IDs request
        if (ids != null) {
            val idList = ids.split(",")
            val items =
                idList.mapNotNull { id ->
                    libraryQueries.getTrack(EntityId(id))?.toBaseItem(favTrackIds)
                        ?: libraryQueries.getAlbum(EntityId(id))?.toBaseItem(favTrackIds)
                        ?: libraryQueries.getArtist(EntityId(id))?.toBaseItem()
                }
            return ResponseEntity.ok(ItemsResult(items, items.size, startIndex))
        }

        // Handle search
        if (!searchTerm.isNullOrBlank()) {
            val results = libraryQueries.searchText(searchTerm, limit, startIndex)
            val items = mutableListOf<BaseItem>()
            results.artists.forEach { items.add(it.toBaseItem()) }
            results.albums.forEach { items.add(it.toBaseItem(favTrackIds)) }
            results.tracks.forEach { items.add(it.toBaseItem(favTrackIds)) }
            return ResponseEntity.ok(ItemsResult(items, items.size, startIndex))
        }

        // Handle favorites
        if (isFavorite == true && uid != null) {
            val items =
                (preferencesQueries.find(UserId(uid))?.favorites ?: emptyList()).mapNotNull { fav ->
                    libraryQueries.getTrack(EntityId(fav.trackId.value))?.toBaseItem(favTrackIds)
                }
            return ResponseEntity.ok(ItemsResult(items, items.size, startIndex))
        }

        // Handle type-based browsing
        val types = includeItemTypes?.split(",")?.map { it.trim() } ?: emptyList()

        if (parentId != null) {
            // Browse children of parent
            val tracks = libraryQueries.browseTracksByAlbum(EntityId(parentId))
            val items = tracks.map { it.toBaseItem(favTrackIds) }
            return ResponseEntity.ok(ItemsResult(items, items.size, startIndex))
        }

        when {
            "MusicArtist" in types -> {
                val artists = libraryQueries.browseArtists(limit, startIndex)
                val items = artists.map { it.toBaseItem() }
                return ResponseEntity.ok(ItemsResult(items, items.size, startIndex))
            }
            "MusicAlbum" in types -> {
                val albums =
                    if (artistIds != null) {
                        libraryQueries.browseAlbumsByArtist(EntityId(artistIds.split(",").first()))
                    } else {
                        libraryQueries.browseAlbums(limit, startIndex)
                    }
                val items = albums.map { it.toBaseItem(favTrackIds) }
                return ResponseEntity.ok(ItemsResult(items, items.size, startIndex))
            }
            "Audio" in types -> {
                val results = libraryQueries.searchText("", limit, startIndex)
                val items = results.tracks.map { it.toBaseItem(favTrackIds) }
                return ResponseEntity.ok(ItemsResult(items, items.size, startIndex))
            }
            "Playlist" in types && uid != null -> {
                val playlists = playlistQueries.findByOwner(UserId(uid))
                val items =
                    playlists.map { pl ->
                        BaseItem(
                            id = pl.id.value,
                            name = pl.name,
                            type = "Playlist",
                            childCount = pl.tracks.size,
                        )
                    }
                return ResponseEntity.ok(ItemsResult(items, items.size, startIndex))
            }
        }

        // Default: return artists
        val artists = libraryQueries.browseArtists(limit, startIndex)
        val items = artists.map { it.toBaseItem() }
        return ResponseEntity.ok(ItemsResult(items, items.size, startIndex))
    }

    @GetMapping("/UserViews")
    fun getUserViews(principal: Principal?): ResponseEntity<Any> =
        ResponseEntity.ok(
            ItemsResult(
                listOf(
                    BaseItem(
                        id = "music-library",
                        name = "Music",
                        type = "CollectionFolder",
                        collectionType = "music",
                        serverId = "yaytsa",
                    ),
                ),
                1,
            ),
        )

    @GetMapping("/Artists")
    fun getArtists(
        @RequestParam(name = "StartIndex", required = false, defaultValue = "0") startIndex: Int,
        @RequestParam(name = "Limit", required = false, defaultValue = "50") limit: Int,
        @RequestParam(name = "SearchTerm", required = false) searchTerm: String?,
        principal: Principal?,
    ): ResponseEntity<Any> {
        val artists =
            if (!searchTerm.isNullOrBlank()) {
                libraryQueries.searchText(searchTerm, limit, startIndex).artists
            } else {
                libraryQueries.browseArtists(limit, startIndex)
            }
        val items = artists.map { it.toBaseItem() }
        return ResponseEntity.ok(ItemsResult(items, items.size, startIndex))
    }

    @GetMapping("/Artists/AlbumArtists")
    fun getAlbumArtists(
        @RequestParam(name = "StartIndex", required = false, defaultValue = "0") startIndex: Int,
        @RequestParam(name = "Limit", required = false, defaultValue = "50") limit: Int,
        principal: Principal?,
    ): ResponseEntity<Any> {
        val artists = libraryQueries.browseArtists(limit, startIndex)
        val items = artists.map { it.toBaseItem() }
        return ResponseEntity.ok(ItemsResult(items, items.size, startIndex))
    }

    @GetMapping("/Search/Hints")
    fun searchHints(
        @RequestParam(name = "searchTerm", required = false) searchTerm: String?,
        @RequestParam(name = "Limit", required = false, defaultValue = "20") limit: Int,
        principal: Principal?,
    ): ResponseEntity<Any> {
        if (searchTerm.isNullOrBlank()) {
            return ResponseEntity.ok(mapOf("SearchHints" to emptyList<Any>(), "TotalRecordCount" to 0))
        }
        val results = libraryQueries.searchText(searchTerm, limit, 0)
        val hints = mutableListOf<Map<String, Any?>>()
        results.artists.forEach {
            hints.add(mapOf("Id" to it.id.value, "Name" to it.name, "Type" to "MusicArtist", "MatchedTerm" to searchTerm))
        }
        results.albums.forEach {
            hints.add(mapOf("Id" to it.id.value, "Name" to it.name, "Type" to "MusicAlbum", "MatchedTerm" to searchTerm))
        }
        results.tracks.forEach {
            hints.add(mapOf("Id" to it.id.value, "Name" to it.name, "Type" to "Audio", "MatchedTerm" to searchTerm, "RunTimeTicks" to msToTicks(it.durationMs)))
        }
        return ResponseEntity.ok(mapOf("SearchHints" to hints, "TotalRecordCount" to hints.size))
    }

    @GetMapping("/Items/{itemId}/PlaybackInfo")
    fun getPlaybackInfo(
        @PathVariable itemId: String,
        principal: Principal?,
    ): ResponseEntity<Any> {
        val track = libraryQueries.getTrack(EntityId(itemId)) ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(
            mapOf(
                "MediaSources" to listOf(
                    mapOf(
                        "Id" to track.id.value,
                        "Name" to track.name,
                        "RunTimeTicks" to msToTicks(track.durationMs),
                        "SupportsDirectStream" to true,
                        "SupportsDirectPlay" to true,
                        "SupportsTranscoding" to false,
                        "Container" to (track.codec ?: "mp3"),
                        "Type" to "Default",
                    ),
                ),
                "PlaySessionId" to java.util.UUID.randomUUID().toString(),
            ),
        )
    }

    @GetMapping("/Items/{itemId}")
    fun getItem(
        @PathVariable itemId: String,
        @RequestParam(required = false) userId: String?,
        principal: Principal?,
    ): ResponseEntity<Any> {
        val uid = userId ?: principal?.name
        val favTrackIds =
            if (uid != null) {
                (preferencesQueries.find(UserId(uid))?.favorites ?: emptyList())
                    .map { it.trackId.value }
                    .toSet()
            } else {
                emptySet()
            }

        val item =
            libraryQueries.getTrack(EntityId(itemId))?.toBaseItem(favTrackIds)
                ?: libraryQueries.getAlbum(EntityId(itemId))?.toBaseItem(favTrackIds)
                ?: libraryQueries.getArtist(EntityId(itemId))?.toBaseItem()
                ?: return ResponseEntity.notFound().build()

        return ResponseEntity.ok(item)
    }

    // --- Mappers ---

    private fun Track.toBaseItem(favTrackIds: Set<String> = emptySet()) =
        BaseItem(
            id = id.value,
            name = name,
            type = "Audio",
            album = albumId?.let { libraryQueries.getAlbum(it)?.name },
            albumId = albumId?.value,
            runTimeTicks = msToTicks(durationMs),
            imageTags = coverImagePath?.let { mapOf("Primary" to id.value) },
            userData = UserItemData(isFavorite = id.value in favTrackIds),
            genres = genre?.let { listOf(it) },
            sortName = sortName,
            parentId = albumId?.value,
        )

    private fun Album.toBaseItem(favTrackIds: Set<String> = emptySet()) =
        BaseItem(
            id = id.value,
            name = name,
            type = "MusicAlbum",
            artistItems =
                artistId?.let { aid ->
                    libraryQueries.getArtist(aid)?.let { listOf(NameIdPair(it.name, it.id.value)) }
                },
            artists =
                artistId?.let { aid ->
                    libraryQueries.getArtist(aid)?.let { listOf(it.name) }
                },
            imageTags = coverImagePath?.let { mapOf("Primary" to id.value) },
            sortName = sortName,
            parentId = artistId?.value,
        )

    private fun Artist.toBaseItem() =
        BaseItem(
            id = id.value,
            name = name,
            type = "MusicArtist",
            imageTags = coverImagePath?.let { mapOf("Primary" to id.value) },
            sortName = sortName,
        )
}
