package dev.yaytsa.adapterjellyfin

import dev.yaytsa.adaptershared.BaseItem
import dev.yaytsa.adaptershared.NameIdPair
import dev.yaytsa.adaptershared.TrackLookups
import dev.yaytsa.adaptershared.UserItemData
import dev.yaytsa.adaptershared.msToTicks
import dev.yaytsa.adaptershared.toJellyfinBaseItem
import dev.yaytsa.application.library.LibraryQueries
import dev.yaytsa.application.playback.ResumePositionService
import dev.yaytsa.application.playback.ResumeStatus
import dev.yaytsa.application.playlists.PlaylistQueries
import dev.yaytsa.application.preferences.PreferencesQueries
import dev.yaytsa.domain.library.Album
import dev.yaytsa.domain.library.Artist
import dev.yaytsa.domain.library.Track
import dev.yaytsa.domain.playlists.PlaylistAggregate
import dev.yaytsa.domain.playlists.PlaylistId
import dev.yaytsa.shared.EntityId
import dev.yaytsa.shared.UserId
import org.springframework.http.HttpStatus
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
    private val musicSurfaceFilter: dev.yaytsa.application.recommendation.MusicSurfaceFilter,
    private val resumePositionService: ResumePositionService,
    private val playHistoryQuery: dev.yaytsa.application.playback.port.PlayHistoryQueryPort,
) {
    // Hydrate per-(user,item) resume into UserData.PlaybackPositionTicks so clients seek-on-load.
    // No-op for non-Audio items. Finished books report ticks=0 so playing them restarts cleanly.
    private fun withResume(
        items: List<BaseItem>,
        uid: String?,
    ): List<BaseItem> {
        if (uid == null) return items
        val trackIds = items.filter { it.type == "Audio" }.map { it.id }.toSet()
        if (trackIds.isEmpty()) return items
        val resume = resumePositionService.findByItemIds(UserId(uid), trackIds)
        if (resume.isEmpty()) return items
        return items.map { item ->
            val r = resume[item.id]
            if (r == null || item.type != "Audio") {
                item
            } else {
                val finished = r.status == ResumeStatus.FINISHED
                item.copy(
                    userData =
                        (item.userData ?: UserItemData()).copy(
                            playbackPositionTicks = if (finished) 0L else (msToTicks(r.positionMs) ?: 0L),
                            played = finished,
                        ),
                )
            }
        }
    }

    @GetMapping("/Items")
    fun getItems(
        @RequestParam(required = false) userId: String?,
        @RequestParam(name = "ParentId", required = false) parentId: String?,
        @RequestParam(name = "IncludeItemTypes", required = false) includeItemTypes: String?,
        @RequestParam(name = "Recursive", required = false) recursive: Boolean?,
        @RequestParam(name = "SortBy", required = false) sortBy: String?,
        @RequestParam(name = "SortOrder", required = false) sortOrder: String?,
        @RequestParam(name = "StartIndex", required = false, defaultValue = "0") startIndex: Int,
        @RequestParam(name = "Limit", required = false, defaultValue = "50") limitParam: Int,
        @RequestParam(name = "SearchTerm", required = false) searchTerm: String?,
        @RequestParam(name = "ArtistIds", required = false) artistIds: String?,
        @RequestParam(name = "AlbumIds", required = false) albumIds: String?,
        @RequestParam(name = "IsFavorite", required = false) isFavorite: Boolean?,
        @RequestParam(name = "Ids", required = false) ids: String?,
        @RequestParam(name = "ExcludeGenres", required = false) excludeGenres: String?,
        principal: Principal?,
    ): ResponseEntity<Any> {
        if (userId != null && userId != principal?.name) return ResponseEntity.status(HttpStatus.FORBIDDEN).build()
        val uid = principal?.name
        val limit = limitParam.coerceIn(1, LibraryQueries.MAX_PAGE_SIZE)
        val excludedGenres =
            excludeGenres
                ?.split(",")
                ?.map { it.trim().lowercase() }
                ?.filter { it.isNotEmpty() }
                .orEmpty()
        val favTrackIds =
            if (uid != null) {
                preferencesQueries.findFavoriteTrackIds(UserId(uid))
            } else {
                emptySet()
            }

        // Handle specific IDs request
        if (ids != null) {
            val idList = ids.split(",")
            val tracks = libraryQueries.getTracksByIds(idList.map { EntityId(it) })
            val trackLookups = tracksLookups(tracks)
            val items =
                idList.mapNotNull { id ->
                    val asTrack = tracks.firstOrNull { it.id.value == id }
                    if (asTrack != null) {
                        asTrack.toBaseItem(favTrackIds, trackLookups)
                    } else {
                        libraryQueries.getAlbum(EntityId(id))?.let { it.toBaseItem(favTrackIds, albumArtistNames(listOf(it))) }
                            ?: libraryQueries.getArtist(EntityId(id))?.toBaseItem()
                    }
                }
            return ResponseEntity.ok(ItemsResult(withResume(items, uid), items.size, startIndex))
        }

        // Handle search
        if (!searchTerm.isNullOrBlank()) {
            val results = libraryQueries.searchText(searchTerm, limit, startIndex, excludedGenres)
            val trackLookups = tracksLookups(results.tracks)
            val albumNames = albumArtistNames(results.albums)
            val items = mutableListOf<BaseItem>()
            results.artists.forEach { items.add(it.toBaseItem()) }
            results.albums.forEach { items.add(it.toBaseItem(favTrackIds, albumNames)) }
            results.tracks.forEach { items.add(it.toBaseItem(favTrackIds, trackLookups)) }
            val total =
                libraryQueries.countTextSearchArtists(searchTerm) +
                    libraryQueries.countTextSearchAlbums(searchTerm) +
                    libraryQueries.countTextSearchTracks(searchTerm)
            return ResponseEntity.ok(ItemsResult(withResume(items, uid), total, startIndex))
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
            val items = withResume(pageTracks.map { it.toBaseItem(favTrackIds, trackLookups) }, uid)
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
                val items = withResume(page.map { it.toBaseItem(favTrackIds, trackLookups) }, uid)
                return ResponseEntity.ok(ItemsResult(items, tracks.size, startIndex))
            }

            if (libraryQueries.getArtist(parentEntity) != null) {
                if (recursive == true || "Audio" in types) {
                    val page = libraryQueries.browseTracksByArtist(parentEntity, pageSize, pageStart)
                    val total = libraryQueries.countTracksByArtist(parentEntity)
                    val trackLookups = tracksLookups(page)
                    val items = withResume(page.map { it.toBaseItem(favTrackIds, trackLookups) }, uid)
                    return ResponseEntity.ok(ItemsResult(items, total, startIndex))
                }
                val albums = libraryQueries.browseAlbumsByArtist(parentEntity)
                val page = albums.drop(pageStart).take(pageSize)
                val albumNames = albumArtistNames(page)
                val items = page.map { it.toBaseItem(favTrackIds, albumNames) }
                return ResponseEntity.ok(ItemsResult(items, albums.size, startIndex))
            }

            // Unknown parent (e.g. playlist or stale id): browse as an album for
            // backward compatibility, paginated and with the true total.
            val tracks = libraryQueries.browseTracksByAlbum(parentEntity)
            val page = tracks.drop(pageStart).take(pageSize)
            val trackLookups = tracksLookups(page)
            val items = withResume(page.map { it.toBaseItem(favTrackIds, trackLookups) }, uid)
            return ResponseEntity.ok(ItemsResult(items, tracks.size, startIndex))
        }

        when {
            "MusicArtist" in types -> {
                // Frontend "Recently Played" sort (SortBy=DatePlayed, from the library sort menu —
                // NOT the Home Daily Mix widget, which calls the dedicated /v1/recommend/daily-mix
                // endpoint): order artists by the most recent play of any of their tracks, read from
                // play history. A song play implies its artist, so no explicit "play artist" is
                // needed. Fires even with ExcludeGenres set (the PWA always sends
                // ExcludeGenres=audiobook,audiobooks) — excluded-genre tracks are filtered out inside,
                // which is why the old excludedGenres.isEmpty() guard is gone (this addresses #266).
                // First page only; falls back to the deterministic browse when there is no history.
                if (uid != null && isPersonalizedSort(sortBy) && startIndex == 0) {
                    val recent = buildRecentlyPlayedArtists(UserId(uid), limit, excludedGenres)
                    if (recent.isNotEmpty()) {
                        return ResponseEntity.ok(ItemsResult(recent.withAlbumCounts(), recent.size, startIndex))
                    }
                }
                val artists =
                    if (excludedGenres.isEmpty()) {
                        libraryQueries.browseArtists(limit, startIndex, sortBy ?: "SortName", sortOrder ?: "Ascending")
                    } else {
                        libraryQueries.browseArtistsExcludingGenres(excludedGenres, limit, startIndex, sortBy ?: "SortName", sortOrder ?: "Ascending")
                    }
                val total =
                    if (excludedGenres.isEmpty()) {
                        libraryQueries.countArtists()
                    } else {
                        libraryQueries.countArtistsExcludingGenres(excludedGenres)
                    }
                val items = artists.withAlbumCounts()
                return ResponseEntity.ok(ItemsResult(items, total, startIndex))
            }
            "MusicAlbum" in types -> {
                if (artistIds == null && uid != null && isPersonalizedSort(sortBy) && startIndex == 0) {
                    val recent = buildRecentlyPlayedAlbums(UserId(uid), limit, excludedGenres)
                    if (recent.isNotEmpty()) {
                        val names = albumArtistNames(recent)
                        val items = recent.map { it.toBaseItem(favTrackIds, names) }
                        return ResponseEntity.ok(ItemsResult(items, recent.size, startIndex))
                    }
                }
                val (albums, total) =
                    if (artistIds != null) {
                        val list = libraryQueries.browseAlbumsByArtist(EntityId(artistIds.split(",").first()))
                        list to list.size
                    } else if (excludedGenres.isEmpty()) {
                        libraryQueries.browseAlbums(limit, startIndex, sortBy ?: "SortName", sortOrder ?: "Ascending") to libraryQueries.countAlbums()
                    } else {
                        libraryQueries.browseAlbumsExcludingGenres(excludedGenres, limit, startIndex, sortBy ?: "SortName", sortOrder ?: "Ascending") to
                            libraryQueries.countAlbumsExcludingGenres(excludedGenres)
                    }
                val albumNames = albumArtistNames(albums)
                val items = albums.map { it.toBaseItem(favTrackIds, albumNames) }
                return ResponseEntity.ok(ItemsResult(items, total, startIndex))
            }
            "Audio" in types -> {
                // Personalized order — reachable only via the library "Recently Played" sort
                // menu (SortBy=DatePlayed), NOT the Home Daily Mix widget (that calls the
                // dedicated /v1/recommend/daily-mix endpoint instead). Backend has no DatePlayed
                // column on entities; instead we serve the user's top ML affinities (real "what
                // you actually listened to"), then fold in favorites, then fill from a varied
                // library sample. Same excludedGenres reachability gap as MusicArtist/MusicAlbum
                // above — see #266.
                // This is a bounded top-N approximation with no offset/cursor concept, so
                // TotalRecordCount must equal the personalized page's own size (matching the
                // MusicAlbum/MusicArtist branches below) — reporting the full library count
                // here would make infinite-scroll request a page 1 that falls back to a
                // deterministic browse query with no "DatePlayed" column to continue from,
                // reproducing/skipping tracks already shown on page 0.
                val isPersonalized = uid != null && isPersonalizedSort(sortBy)
                if (isPersonalized && startIndex == 0 && excludedGenres.isEmpty()) {
                    val personalized = buildPersonalizedTracks(UserId(uid!!), limit)
                    if (personalized.isNotEmpty()) {
                        val lookups = tracksLookups(personalized)
                        val items = withResume(personalized.map { it.toBaseItem(favTrackIds, lookups) }, uid)
                        return ResponseEntity.ok(ItemsResult(items, personalized.size, startIndex))
                    }
                }
                val browsed =
                    libraryQueries.browseTracksExcludingGenres(
                        excludedGenres,
                        limit,
                        startIndex,
                        sortBy ?: "SortName",
                        sortOrder ?: "Ascending",
                    )
                val trackLookups = tracksLookups(browsed)
                val items = withResume(browsed.map { it.toBaseItem(favTrackIds, trackLookups) }, uid)
                return ResponseEntity.ok(ItemsResult(items, libraryQueries.countTracksExcludingGenres(excludedGenres), startIndex))
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
        @RequestParam(name = "Limit", required = false, defaultValue = "50") limitParam: Int,
        @RequestParam(name = "SearchTerm", required = false) searchTerm: String?,
        principal: Principal?,
    ): ResponseEntity<Any> {
        val limit = limitParam.coerceIn(1, LibraryQueries.MAX_PAGE_SIZE)
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
        @RequestParam(name = "Limit", required = false, defaultValue = "50") limitParam: Int,
        principal: Principal?,
    ): ResponseEntity<Any> {
        val artists = libraryQueries.browseArtists(limitParam.coerceIn(1, LibraryQueries.MAX_PAGE_SIZE), startIndex)
        val items = artists.withAlbumCounts()
        return ResponseEntity.ok(ItemsResult(items, libraryQueries.countArtists(), startIndex))
    }

    @GetMapping("/Search/Hints")
    fun searchHints(
        @RequestParam(name = "searchTerm", required = false) searchTerm: String?,
        @RequestParam(name = "Limit", required = false, defaultValue = "20") limitParam: Int,
        principal: Principal?,
    ): ResponseEntity<Any> {
        if (searchTerm.isNullOrBlank()) {
            return ResponseEntity.ok(mapOf("SearchHints" to emptyList<Any>(), "TotalRecordCount" to 0))
        }
        val results = libraryQueries.searchText(searchTerm, limitParam.coerceIn(1, LibraryQueries.MAX_PAGE_SIZE), 0)
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
        if (userId != null && userId != principal?.name) return ResponseEntity.status(HttpStatus.FORBIDDEN).build()
        val uid = principal?.name
        val favTrackIds =
            if (uid != null) {
                preferencesQueries.findFavoriteTrackIds(UserId(uid))
            } else {
                emptySet()
            }

        val track = libraryQueries.getTrack(EntityId(itemId))
        val item =
            track?.toBaseItem(favTrackIds, tracksLookups(listOf(track)))
                ?: libraryQueries.getAlbum(EntityId(itemId))?.let { it.toBaseItem(favTrackIds, albumArtistNames(listOf(it))) }
                ?: libraryQueries.getArtist(EntityId(itemId))?.toBaseItem()
                // BOLA guard (OWASP API1:2023): this is a query, so it never goes through
                // PlaylistHandler's `snapshot.owner != ctx.userId` check — without the
                // isPublic/owner filter, any authenticated user could learn the name and
                // track count of any other user's private playlist just by its UUID.
                ?: runCatching { playlistQueries.find(PlaylistId(itemId)) }
                    .getOrNull()
                    ?.takeIf { it.isPublic || it.owner.value == uid }
                    ?.toBaseItem()
                ?: return ResponseEntity.notFound().build()

        return ResponseEntity.ok(withResume(listOf(item), uid).first())
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

    // Batch-resolve album artist names in a single query, mirroring tracksLookups. Avoids the
    // N+1 where every album in a page triggered two getArtist() calls (entity+artist+image each).
    private fun albumArtistNames(albums: List<Album>): Map<EntityId, String> {
        val artistIds = albums.mapNotNull { it.artistId }.toSet()
        if (artistIds.isEmpty()) return emptyMap()
        return libraryQueries.getEntityNamesByIds(artistIds)
    }

    private fun Album.toBaseItem(
        favTrackIds: Set<String> = emptySet(),
        artistNames: Map<EntityId, String> = emptyMap(),
    ) = BaseItem(
        id = id.value,
        name = name,
        type = "MusicAlbum",
        artistItems =
            artistId?.let { aid ->
                artistNames[aid]?.let { listOf(NameIdPair(it, aid.value)) }
            },
        artists =
            artistId?.let { aid ->
                artistNames[aid]?.let { listOf(it) }
            },
        imageTags = coverImagePath?.let { mapOf("Primary" to id.value) },
        sortName = sortName,
        parentId = artistId?.value,
        dateCreated = createdAt?.toString(),
        productionYear = productionYear,
    )

    // "Recently Played" / "Personalized" / "PlayCount" all map to the same affinity-driven ordering:
    // the library has no per-item played-at column, so these are served from ML affinities + favorites.
    private fun isPersonalizedSort(sortBy: String?): Boolean = sortBy in setOf("DatePlayed", "Personalized", "PlayCount")

    // Tracks the user most recently played (most-recent-first), from play history. getTracksByIds
    // preserves the recency order and drops vanished tracks. Tracks whose primary genre is excluded
    // are filtered out (the PWA always excludes audiobooks) — a per-track primary-genre approximation
    // of the browse genre-exclusion, adequate here since audiobook tracks carry an audiobook genre.
    private fun recentlyPlayedTracks(
        userId: UserId,
        excludedGenres: Collection<String>,
    ): List<Track> {
        val ids = playHistoryQuery.recentlyPlayedTrackIds(userId, RECENT_PLAY_TRACK_POOL).map { EntityId(it.value) }
        val tracks = libraryQueries.getTracksByIds(ids)
        if (excludedGenres.isEmpty()) return tracks
        val excluded = excludedGenres.map { it.lowercase() }.toSet()
        return tracks.filter { it.genre?.lowercase() !in excluded }
    }

    // Albums ordered by the most recent play of any of their tracks. Because recentlyPlayedTracks is
    // already recency-ordered, an album's first appearance is its most recent play, so distinct()
    // preserves that ordering. Empty when there is no (non-excluded) play history, so the caller
    // falls back to the deterministic browse.
    private fun buildRecentlyPlayedAlbums(
        userId: UserId,
        limit: Int,
        excludedGenres: Collection<String>,
    ): List<Album> {
        val orderedAlbumIds = recentlyPlayedTracks(userId, excludedGenres).mapNotNull { it.albumId }.distinct().take(limit)
        return if (orderedAlbumIds.isEmpty()) emptyList() else libraryQueries.getAlbumsByIds(orderedAlbumIds)
    }

    // Artists ordered by the most recent play of any of their tracks (album artist), same principle.
    private fun buildRecentlyPlayedArtists(
        userId: UserId,
        limit: Int,
        excludedGenres: Collection<String>,
    ): List<Artist> {
        val orderedArtistIds = recentlyPlayedTracks(userId, excludedGenres).mapNotNull { it.albumArtistId }.distinct().take(limit)
        return if (orderedArtistIds.isEmpty()) emptyList() else libraryQueries.getArtistsByIds(orderedArtistIds)
    }

    private fun buildPersonalizedTracks(
        userId: UserId,
        limit: Int,
    ): List<dev.yaytsa.domain.library.Track> {
        val out = mutableListOf<dev.yaytsa.domain.library.Track>()

        // Affinities first, then favorites — batch-loaded in one query instead of one getTrack()
        // per id. Order is preserved by re-indexing against the requested id sequence; vanished
        // tracks simply drop out.
        val affinityIds = mlQuery.getTopAffinities(userId, limit).map { it.trackId.value }
        val favoriteIds =
            preferencesQueries
                .find(userId)
                ?.favorites
                .orEmpty()
                .take(limit)
                .map { it.trackId.value }
        val orderedIds = (affinityIds + favoriteIds).distinct()
        if (orderedIds.isNotEmpty()) {
            val byId = libraryQueries.getTracksByIds(orderedIds.map { EntityId(it) }).associateBy { it.id.value }
            orderedIds.forEach { id ->
                if (out.size >= limit) return@forEach
                byId[id]?.let { out.add(it) }
            }
        }
        val seen = out.mapTo(mutableSetOf()) { it.id.value }

        // Random sample for users with empty affinity+favorites — anything is better than the
        // alphabetical SortName fallback (a homepage opening with "#1, '(515)", '(D)eath'..."
        // reads as a bug). musicSurfaceFilter (audiobooks/red-lines) runs AFTER this, so a random
        // draw can come back under `limit` post-filter; retry with fresh draws until the filtered
        // count catches up, the library is exhausted, or the attempt cap is hit — otherwise a
        // library with enough non-music genres mixed in silently short-pages every request.
        var filtered = musicSurfaceFilter.filter(out, userId)
        var attempt = 0
        while (filtered.size < limit && attempt < MAX_RANDOM_FILL_ATTEMPTS) {
            // Based on the post-filter shortfall, not out.size — out keeps growing with
            // filtered-out candidates too, so out.size alone would hit `limit` and stop the
            // loop before filtered.size ever catches up.
            val needed = limit - filtered.size
            val drawn = libraryQueries.browseTracksRandom(needed)
            // Only a genuinely empty result proves the pool is exhausted. A draw that comes back
            // non-empty but entirely duplicates of what's already `seen` is just bad luck on a
            // small `needed` request, not proof there's nothing left — keep retrying up to the
            // attempt cap instead of giving up on one unlucky draw.
            if (drawn.isEmpty()) break
            drawn.forEach { track -> if (seen.add(track.id.value)) out.add(track) }
            filtered = musicSurfaceFilter.filter(out, userId)
            attempt++
        }

        return filtered.take(limit)
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

    private companion object {
        private const val MAX_RANDOM_FILL_ATTEMPTS = 5

        // How many distinct recently-played tracks to fold into album/artist "Recently Played".
        // Bounds the play-history scan while covering enough history to fill a page after collapsing
        // to albums/artists and dropping excluded-genre tracks (many tracks map to one album/artist).
        private const val RECENT_PLAY_TRACK_POOL = 500
    }
}
