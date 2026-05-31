package dev.yaytsa.adapterjellyfin

import dev.yaytsa.adaptershared.BaseItem
import dev.yaytsa.adaptershared.NameIdPair
import dev.yaytsa.adaptershared.TrackLookups
import dev.yaytsa.adaptershared.msToTicks
import dev.yaytsa.adaptershared.toJellyfinBaseItem
import dev.yaytsa.application.library.LibraryQueries
import dev.yaytsa.application.playlists.PlaylistQueries
import dev.yaytsa.application.preferences.PreferencesQueries
import dev.yaytsa.domain.library.Album
import dev.yaytsa.domain.library.Artist
import dev.yaytsa.domain.library.Track
import dev.yaytsa.domain.playlists.PlaylistAggregate
import dev.yaytsa.domain.playlists.PlaylistId
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
    private val mlQuery: dev.yaytsa.application.ml.port.MlQueryPort,
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
            val tracks = idList.mapNotNull { libraryQueries.getTrack(EntityId(it)) }
            val trackLookups = tracksLookups(tracks)
            val items =
                idList.mapNotNull { id ->
                    val asTrack = tracks.firstOrNull { it.id.value == id }
                    if (asTrack != null) {
                        asTrack.toBaseItem(favTrackIds, trackLookups)
                    } else {
                        libraryQueries.getAlbum(EntityId(id))?.toBaseItem(favTrackIds)
                            ?: libraryQueries.getArtist(EntityId(id))?.toBaseItem()
                    }
                }
            return ResponseEntity.ok(ItemsResult(items, items.size, startIndex))
        }

        // Handle search
        if (!searchTerm.isNullOrBlank()) {
            val results = libraryQueries.searchText(searchTerm, limit, startIndex)
            val trackLookups = tracksLookups(results.tracks)
            val items = mutableListOf<BaseItem>()
            results.artists.forEach { items.add(it.toBaseItem()) }
            results.albums.forEach { items.add(it.toBaseItem(favTrackIds)) }
            results.tracks.forEach { items.add(it.toBaseItem(favTrackIds, trackLookups)) }
            val total =
                libraryQueries.countTextSearchArtists(searchTerm) +
                    libraryQueries.countTextSearchAlbums(searchTerm) +
                    libraryQueries.countTextSearchTracks(searchTerm)
            return ResponseEntity.ok(ItemsResult(items, total, startIndex))
        }

        // Handle favorites. Favorites live in the in-memory preferences aggregate, so sort there
        // and batch-load only the requested page's tracks — never one getTrack() per favorite.
        if (isFavorite == true && uid != null) {
            val favorites = preferencesQueries.find(UserId(uid))?.favorites ?: emptyList()
            // Drop favorites whose track has vanished (deleted/renamed) via a single existence
            // query, so TotalRecordCount matches the items the client can actually page through —
            // otherwise infinite scroll keeps requesting a page that never fills.
            val existingIds = if (favorites.isEmpty()) emptySet() else libraryQueries.trackIdsExist(favorites.map { it.trackId }.toSet())
            val resolvable = favorites.filter { it.trackId in existingIds }
            val ascending = !sortOrder.equals("Descending", ignoreCase = true)
            val ordered =
                when (sortBy) {
                    "DateFavorited" -> resolvable.sortedBy { it.favoritedAt }
                    "SortName" -> {
                        val names = libraryQueries.getEntityNamesByIds(resolvable.map { EntityId(it.trackId.value) }.toSet())
                        resolvable.sortedBy { (names[EntityId(it.trackId.value)] ?: "").lowercase() }
                    }
                    else -> resolvable.sortedBy { it.position }
                }.let { if (ascending) it else it.reversed() }
            val pageFavs = ordered.drop(startIndex.coerceAtLeast(0)).take(limit.coerceIn(1, LibraryQueries.MAX_PAGE_SIZE))
            val pageTracks = libraryQueries.getTracksByIds(pageFavs.map { EntityId(it.trackId.value) })
            val trackLookups = tracksLookups(pageTracks)
            val items = pageTracks.map { it.toBaseItem(favTrackIds, trackLookups) }
            return ResponseEntity.ok(ItemsResult(items, resolvable.size, startIndex))
        }

        // Handle type-based browsing
        val types = includeItemTypes?.split(",")?.map { it.trim() } ?: emptyList()

        if (parentId != null) {
            val parentEntity = EntityId(parentId)
            val pageStart = startIndex.coerceAtLeast(0)
            val pageSize = limit.coerceIn(1, LibraryQueries.MAX_PAGE_SIZE)

            if (libraryQueries.getAlbum(parentEntity) != null) {
                val tracks = libraryQueries.browseTracksByAlbum(parentEntity)
                val page = tracks.drop(pageStart).take(pageSize)
                val trackLookups = tracksLookups(page)
                val items = page.map { it.toBaseItem(favTrackIds, trackLookups) }
                return ResponseEntity.ok(ItemsResult(items, tracks.size, startIndex))
            }

            if (libraryQueries.getArtist(parentEntity) != null) {
                if (recursive == true || "Audio" in types) {
                    val page = libraryQueries.browseTracksByArtist(parentEntity, pageSize, pageStart)
                    val total = libraryQueries.countTracksByArtist(parentEntity)
                    val trackLookups = tracksLookups(page)
                    val items = page.map { it.toBaseItem(favTrackIds, trackLookups) }
                    return ResponseEntity.ok(ItemsResult(items, total, startIndex))
                }
                val albums = libraryQueries.browseAlbumsByArtist(parentEntity)
                val page = albums.drop(pageStart).take(pageSize)
                val items = page.map { it.toBaseItem(favTrackIds) }
                return ResponseEntity.ok(ItemsResult(items, albums.size, startIndex))
            }

            // Unknown parent (e.g. playlist or stale id): browse as an album for
            // backward compatibility, paginated and with the true total.
            val tracks = libraryQueries.browseTracksByAlbum(parentEntity)
            val page = tracks.drop(pageStart).take(pageSize)
            val trackLookups = tracksLookups(page)
            val items = page.map { it.toBaseItem(favTrackIds, trackLookups) }
            return ResponseEntity.ok(ItemsResult(items, tracks.size, startIndex))
        }

        when {
            "MusicArtist" in types -> {
                val artists = libraryQueries.browseArtists(limit, startIndex)
                val items = artists.withAlbumCounts()
                return ResponseEntity.ok(ItemsResult(items, libraryQueries.countArtists(), startIndex))
            }
            "MusicAlbum" in types -> {
                val (albums, total) =
                    if (artistIds != null) {
                        val list = libraryQueries.browseAlbumsByArtist(EntityId(artistIds.split(",").first()))
                        list to list.size
                    } else {
                        libraryQueries.browseAlbums(limit, startIndex) to libraryQueries.countAlbums()
                    }
                val items = albums.map { it.toBaseItem(favTrackIds) }
                return ResponseEntity.ok(ItemsResult(items, total, startIndex))
            }
            "Audio" in types -> {
                // Personalized order — used by frontend Daily Mix (SortBy=DatePlayed).
                // Backend has no DatePlayed column on entities; instead we serve the
                // user's top ML affinities (real "what you actually listened to"),
                // then fold in favorites, then fill from a varied library sample.
                val isPersonalized = uid != null && sortBy in setOf("DatePlayed", "Personalized", "PlayCount")
                if (isPersonalized && startIndex == 0) {
                    val personalized = buildPersonalizedTracks(UserId(uid!!), limit)
                    if (personalized.isNotEmpty()) {
                        val lookups = tracksLookups(personalized)
                        val items = personalized.map { it.toBaseItem(favTrackIds, lookups) }
                        return ResponseEntity.ok(ItemsResult(items, libraryQueries.countTracks(), startIndex))
                    }
                }
                val browsed = libraryQueries.browseTracks(limit, startIndex, sortBy ?: "SortName", sortOrder ?: "Ascending")
                val trackLookups = tracksLookups(browsed)
                val items = browsed.map { it.toBaseItem(favTrackIds, trackLookups) }
                return ResponseEntity.ok(ItemsResult(items, libraryQueries.countTracks(), startIndex))
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
        val items = artists.withAlbumCounts()
        return ResponseEntity.ok(ItemsResult(items, libraryQueries.countArtists(), startIndex))
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
        val items = artists.withAlbumCounts()
        val total =
            if (!searchTerm.isNullOrBlank()) {
                libraryQueries.countTextSearchArtists(searchTerm)
            } else {
                libraryQueries.countArtists()
            }
        return ResponseEntity.ok(ItemsResult(items, total, startIndex))
    }

    @GetMapping("/Artists/AlbumArtists")
    fun getAlbumArtists(
        @RequestParam(name = "StartIndex", required = false, defaultValue = "0") startIndex: Int,
        @RequestParam(name = "Limit", required = false, defaultValue = "50") limit: Int,
        principal: Principal?,
    ): ResponseEntity<Any> {
        val artists = libraryQueries.browseArtists(limit, startIndex)
        val items = artists.withAlbumCounts()
        return ResponseEntity.ok(ItemsResult(items, libraryQueries.countArtists(), startIndex))
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
                "MediaSources" to
                    listOf(
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
                "PlaySessionId" to
                    java.util.UUID
                        .randomUUID()
                        .toString(),
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

        val track = libraryQueries.getTrack(EntityId(itemId))
        val item =
            track?.toBaseItem(favTrackIds, tracksLookups(listOf(track)))
                ?: libraryQueries.getAlbum(EntityId(itemId))?.toBaseItem(favTrackIds)
                ?: libraryQueries.getArtist(EntityId(itemId))?.toBaseItem()
                ?: runCatching { playlistQueries.find(PlaylistId(itemId)) }.getOrNull()?.toBaseItem()
                ?: return ResponseEntity.notFound().build()

        return ResponseEntity.ok(item)
    }

    private fun PlaylistAggregate.toBaseItem(): BaseItem =
        BaseItem(
            id = id.value,
            name = name,
            type = "Playlist",
            childCount = tracks.size,
        )

    // --- Mappers ---

    private fun tracksLookups(tracks: List<Track>): TrackLookups {
        val albumIds = tracks.mapNotNull { it.albumId }.toSet()
        val artistIds = tracks.mapNotNull { it.albumArtistId }.toSet()
        return TrackLookups(
            albumNames = libraryQueries.getEntityNamesByIds(albumIds),
            artistNames = libraryQueries.getEntityNamesByIds(artistIds),
        )
    }

    private fun Track.toBaseItem(
        favTrackIds: Set<String> = emptySet(),
        lookups: TrackLookups = TrackLookups(),
    ): BaseItem = toJellyfinBaseItem(favTrackIds, lookups)

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
            dateCreated = createdAt?.toString(),
        )

    private fun buildPersonalizedTracks(
        userId: UserId,
        limit: Int,
    ): List<dev.yaytsa.domain.library.Track> {
        val seen = mutableSetOf<String>()
        val out = mutableListOf<dev.yaytsa.domain.library.Track>()

        mlQuery.getTopAffinities(userId, limit).forEach { aff ->
            if (out.size >= limit) return@forEach
            val track = libraryQueries.getTrack(EntityId(aff.trackId.value)) ?: return@forEach
            if (seen.add(track.id.value)) out.add(track)
        }

        if (out.size < limit) {
            preferencesQueries.find(userId)?.favorites.orEmpty().forEach { fav ->
                if (out.size >= limit) return@forEach
                val track = libraryQueries.getTrack(EntityId(fav.trackId.value)) ?: return@forEach
                if (seen.add(track.id.value)) out.add(track)
            }
        }

        if (out.size < limit) {
            // Random sample for users with empty affinity+favorites.
            // Anything is better than the alphabetical SortName fallback —
            // a homepage opening with "#1, '(515)", '(D)eath'..." reads as a bug.
            libraryQueries.browseTracksRandom(limit - out.size).forEach { track ->
                if (out.size >= limit) return@forEach
                if (seen.add(track.id.value)) out.add(track)
            }
        }

        return out.take(limit)
    }

    private fun Artist.toBaseItem(albumCount: Int? = null) =
        BaseItem(
            id = id.value,
            name = name,
            type = "MusicArtist",
            imageTags = coverImagePath?.let { mapOf("Primary" to id.value) },
            sortName = sortName,
            childCount = albumCount,
        )

    private fun List<Artist>.withAlbumCounts(): List<BaseItem> {
        if (isEmpty()) return emptyList()
        val counts = libraryQueries.countAlbumsByArtistIds(map { it.id }.toSet())
        return map { it.toBaseItem(albumCount = counts[it.id] ?: 0) }
    }
}
