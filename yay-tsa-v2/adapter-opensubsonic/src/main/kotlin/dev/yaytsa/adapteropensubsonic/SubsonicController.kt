package dev.yaytsa.adapteropensubsonic

import dev.yaytsa.adaptershared.AdapterCommandContextFactory
import dev.yaytsa.adaptershared.ChildElement
import dev.yaytsa.adaptershared.SubsonicFailureTranslator
import dev.yaytsa.adaptershared.TrackLookups
import dev.yaytsa.adaptershared.toSubsonicChild
import dev.yaytsa.application.auth.AuthQueries
import dev.yaytsa.application.library.LibraryQueries
import dev.yaytsa.application.playback.ScrobbleService
import dev.yaytsa.application.playlists.PlaylistQueries
import dev.yaytsa.application.playlists.PlaylistUseCases
import dev.yaytsa.application.preferences.PreferencesQueries
import dev.yaytsa.application.preferences.PreferencesUseCases
import dev.yaytsa.application.shared.port.Clock
import dev.yaytsa.domain.auth.UserAggregate
import dev.yaytsa.domain.library.Album
import dev.yaytsa.domain.library.Track
import dev.yaytsa.domain.playlists.AddTracksToPlaylist
import dev.yaytsa.domain.playlists.CreatePlaylist
import dev.yaytsa.domain.playlists.DeletePlaylist
import dev.yaytsa.domain.playlists.PlaylistAggregate
import dev.yaytsa.domain.playlists.PlaylistCommand
import dev.yaytsa.domain.playlists.PlaylistId
import dev.yaytsa.domain.playlists.RemovePlaylistEntriesByPosition
import dev.yaytsa.domain.playlists.RenamePlaylist
import dev.yaytsa.domain.playlists.SetPlaylistVisibility
import dev.yaytsa.domain.playlists.UpdatePlaylistDescription
import dev.yaytsa.domain.preferences.SetFavorite
import dev.yaytsa.domain.preferences.UnsetFavorite
import dev.yaytsa.shared.AggregateVersion
import dev.yaytsa.shared.CommandContext
import dev.yaytsa.shared.CommandResult
import dev.yaytsa.shared.EntityId
import dev.yaytsa.shared.Failure
import dev.yaytsa.shared.TrackId
import dev.yaytsa.shared.UserId
import jakarta.servlet.http.HttpServletRequest
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.security.Principal
import java.time.Instant
import java.util.UUID

class SubsonicApiException(
    val code: Int,
    override val message: String,
) : RuntimeException(message)

@RestController
@RequestMapping("/rest")
class SubsonicController(
    private val libraryQueries: LibraryQueries,
    private val authQueries: AuthQueries,
    private val playlistQueries: PlaylistQueries,
    private val playlistUseCases: PlaylistUseCases,
    private val preferencesQueries: PreferencesQueries,
    private val preferencesUseCases: PreferencesUseCases,
    private val scrobbleService: ScrobbleService,
    private val clock: Clock,
    private val responseWriter: SubsonicResponseWriter,
    @Qualifier("subsonicCommandContextFactory")
    private val ctxFactory: AdapterCommandContextFactory,
    private val failureTranslator: SubsonicFailureTranslator,
) {
    private val log = org.slf4j.LoggerFactory.getLogger(javaClass)

    private fun errorFrom(failure: Failure): SubsonicResponse {
        val payload = failureTranslator.translate(failure)
        return error(payload.code, payload.message)
    }

    private fun responseFor(result: CommandResult<*>): SubsonicResponse =
        when (result) {
            is CommandResult.Success -> ok()
            is CommandResult.Failed -> errorFrom(result.failure)
        }

    private fun safeEntityId(value: String): EntityId? = runCatching { UUID.fromString(value) }.getOrNull()?.let { EntityId(value) }

    private fun notFound(
        entityType: String,
        id: String,
        f: String?,
    ): ResponseEntity<String> = responseWriter.write(errorFrom(Failure.NotFound(entityType, id)), f)

    // --- System ---

    @GetMapping("/ping", "/ping.view")
    fun ping(
        @RequestParam(required = false) f: String?,
    ): ResponseEntity<String> = responseWriter.write(ok(), f)

    @GetMapping("/getLicense", "/getLicense.view")
    fun getLicense(
        @RequestParam(required = false) f: String?,
    ): ResponseEntity<String> = responseWriter.write(ok { copy(license = LicenseDetail(valid = true)) }, f)

    @GetMapping("/getOpenSubsonicExtensions", "/getOpenSubsonicExtensions.view")
    fun getExtensions(
        @RequestParam(required = false) f: String?,
    ): ResponseEntity<String> = responseWriter.write(ok { copy(openSubsonicExtensions = emptyList()) }, f)

    @GetMapping("/getUser", "/getUser.view")
    fun getUser(
        @RequestParam(required = false) username: String?,
        @RequestParam(required = false) f: String?,
        principal: Principal,
    ): ResponseEntity<String> {
        val caller =
            authQueries.findUser(UserId(principal.name))
                ?: return notFound("User", principal.name, f)
        val target = resolveUserForView(caller, username) ?: return notFound("User", username ?: principal.name, f)
        return responseWriter.write(
            ok { copy(user = UserDetail(username = target.username, adminRole = target.isAdmin)) },
            f,
        )
    }

    private fun resolveUserForView(
        caller: UserAggregate,
        username: String?,
    ): UserAggregate? {
        if (username == null || username == caller.username) return caller
        if (!caller.isAdmin) throw SubsonicApiException(50, "Only admins can view other users")
        return authQueries.findByUsername(username)
    }

    @GetMapping("/getMusicFolders", "/getMusicFolders.view")
    fun getMusicFolders(
        @RequestParam(required = false) f: String?,
    ): ResponseEntity<String> = responseWriter.write(ok { copy(musicFolders = MusicFoldersWrapper(listOf(MusicFolderElement("1", "Music")))) }, f)

    // --- Browsing ---

    private fun artistIndexes(): List<ArtistIndex> =
        libraryQueries.browseArtistsGroupedByLetter().map { (letter, list) ->
            ArtistIndex(letter, list.map { ArtistElement(it.id.value, it.name) })
        }

    @GetMapping("/getArtists", "/getArtists.view")
    fun getArtists(
        @RequestParam(required = false) f: String?,
    ): ResponseEntity<String> = responseWriter.write(ok { copy(artists = ArtistsWrapper(index = artistIndexes())) }, f)

    @GetMapping("/getIndexes", "/getIndexes.view")
    fun getIndexes(
        @RequestParam(required = false) f: String?,
    ): ResponseEntity<String> =
        responseWriter.write(
            ok { copy(indexes = IndexesWrapper(lastModified = clock.now().toEpochMilli(), index = artistIndexes())) },
            f,
        )

    @GetMapping("/getMusicDirectory", "/getMusicDirectory.view")
    fun getMusicDirectory(
        @RequestParam id: String,
        @RequestParam(required = false) f: String?,
    ): ResponseEntity<String> {
        val entityId = safeEntityId(id) ?: return notFound("Directory", id, f)
        libraryQueries.getArtist(entityId)?.let { artist ->
            val children = libraryQueries.browseAlbumsByArtist(entityId).map { it.toDirectoryChild(artist.name) }
            return responseWriter.write(
                ok { copy(directory = DirectoryWrapper(id = artist.id.value, name = artist.name, child = children)) },
                f,
            )
        }
        libraryQueries.getAlbum(entityId)?.let { album ->
            val children = libraryQueries.browseTracksByAlbum(entityId).toSubsonicChildren()
            return responseWriter.write(
                ok {
                    copy(
                        directory =
                            DirectoryWrapper(
                                id = album.id.value,
                                parent = album.artistId?.value,
                                name = album.name,
                                child = children,
                            ),
                    )
                },
                f,
            )
        }
        return notFound("Directory", id, f)
    }

    @GetMapping("/getArtist", "/getArtist.view")
    fun getArtist(
        @RequestParam id: String,
        @RequestParam(required = false) f: String?,
    ): ResponseEntity<String> {
        val entityId = safeEntityId(id) ?: return notFound("Artist", id, f)
        val artist = libraryQueries.getArtist(entityId) ?: return notFound("Artist", id, f)
        val albums = libraryQueries.browseAlbumsByArtist(entityId)
        return responseWriter.write(
            ok {
                copy(artist = ArtistDetail(artist.id.value, artist.name, albums.toAlbumElements()))
            },
            f,
        )
    }

    @GetMapping("/getAlbum", "/getAlbum.view")
    fun getAlbum(
        @RequestParam id: String,
        @RequestParam(required = false) f: String?,
    ): ResponseEntity<String> {
        val entityId = safeEntityId(id) ?: return notFound("Album", id, f)
        val album = libraryQueries.getAlbum(entityId) ?: return notFound("Album", id, f)
        val tracks = libraryQueries.browseTracksByAlbum(entityId)
        val artist = album.artistId?.let { libraryQueries.getArtist(it) }
        return responseWriter.write(
            ok {
                copy(
                    album =
                        AlbumDetail(
                            album.id.value,
                            album.name,
                            artist?.name,
                            artist?.id?.value,
                            album.releaseDate?.year,
                            tracks.toSubsonicChildren(),
                            album.coverImagePath?.let { album.id.value },
                        ),
                )
            },
            f,
        )
    }

    @GetMapping("/getSong", "/getSong.view")
    fun getSong(
        @RequestParam id: String,
        @RequestParam(required = false) f: String?,
    ): ResponseEntity<String> {
        val entityId = safeEntityId(id) ?: return notFound("Song", id, f)
        val track = libraryQueries.getTrack(entityId) ?: return notFound("Song", id, f)
        return responseWriter.write(ok { copy(song = track.toSubsonicChild(tracksLookups(listOf(track)))) }, f)
    }

    private fun tracksLookups(tracks: List<Track>): TrackLookups =
        TrackLookups(
            albumNames = libraryQueries.getEntityNamesByIds(tracks.mapNotNull { it.albumId }.toSet()),
            artistNames = libraryQueries.getEntityNamesByIds(tracks.mapNotNull { it.albumArtistId }.toSet()),
        )

    private fun List<Track>.toSubsonicChildren() = tracksLookups(this).let { lookups -> map { it.toSubsonicChild(lookups) } }

    @GetMapping("/getAlbumList", "/getAlbumList.view")
    fun getAlbumList(
        @RequestParam type: String,
        @RequestParam(defaultValue = "10") size: Int,
        @RequestParam(defaultValue = "0") offset: Int,
        @RequestParam(required = false) fromYear: Int?,
        @RequestParam(required = false) toYear: Int?,
        @RequestParam(required = false) genre: String?,
        @RequestParam(required = false) f: String?,
    ): ResponseEntity<String> {
        val albums = albumsForListType(type, size, offset, fromYear, toYear, genre)
        val artistNames = libraryQueries.getEntityNamesByIds(albums.mapNotNull { it.artistId }.toSet())
        return responseWriter.write(
            ok { copy(albumList = AlbumListV1Wrapper(albums.map { it.toDirectoryChild(it.artistId?.let { aid -> artistNames[aid] }) })) },
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
    ): ResponseEntity<String> {
        val albums = albumsForListType(type, size, offset, fromYear, toYear, genre)
        return responseWriter.write(ok { copy(albumList2 = AlbumListWrapper(albums.toAlbumElements())) }, f)
    }

    private fun albumsForListType(
        type: String,
        size: Int,
        offset: Int,
        fromYear: Int?,
        toYear: Int?,
        genre: String?,
    ): List<Album> =
        when (type.trim()) {
            "random" -> libraryQueries.browseAlbumsRandom(size)
            "newest", "recent", "frequent", "highest" -> libraryQueries.browseAlbumsByCreatedDesc(size, offset)
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
            "starred" -> emptyList()
            else -> libraryQueries.browseAlbums(size.coerceIn(1, 500), offset.coerceAtLeast(0))
        }

    // --- Search ---

    @GetMapping("/search3", "/search3.view")
    fun search3(
        @RequestParam(defaultValue = "") query: String,
        @RequestParam(defaultValue = "20") artistCount: Int,
        @RequestParam(defaultValue = "20") albumCount: Int,
        @RequestParam(defaultValue = "20") songCount: Int,
        @RequestParam(defaultValue = "0") artistOffset: Int,
        @RequestParam(defaultValue = "0") albumOffset: Int,
        @RequestParam(defaultValue = "0") songOffset: Int,
        @RequestParam(required = false) f: String?,
    ): ResponseEntity<String> {
        val q = query.trim().trim('"').trim()
        val result =
            if (q.isEmpty()) {
                SearchResult3(
                    artist =
                        takeIfPositive(artistCount) {
                            libraryQueries.browseArtists(artistCount, artistOffset).map { ArtistElement(it.id.value, it.name) }
                        },
                    album = takeIfPositive(albumCount) { libraryQueries.browseAlbums(albumCount, albumOffset).toAlbumElements() },
                    song =
                        takeIfPositive(songCount) {
                            libraryQueries.browseTracks(songCount, songOffset, "SortName", "Ascending").toSubsonicChildren()
                        },
                )
            } else {
                SearchResult3(
                    artist =
                        takeIfPositive(artistCount) {
                            libraryQueries.searchText(q, artistCount, artistOffset).artists.map { ArtistElement(it.id.value, it.name) }
                        },
                    album = takeIfPositive(albumCount) { libraryQueries.searchText(q, albumCount, albumOffset).albums.toAlbumElements() },
                    song = takeIfPositive(songCount) { libraryQueries.searchText(q, songCount, songOffset).tracks.toSubsonicChildren() },
                )
            }
        return responseWriter.write(ok { copy(searchResult3 = result) }, f)
    }

    private fun <T> takeIfPositive(
        count: Int,
        block: () -> List<T>,
    ): List<T> = if (count > 0) block() else emptyList()

    // --- Favorites ---

    @GetMapping("/star", "/star.view")
    fun star(
        @RequestParam(required = false) id: List<String>?,
        @RequestParam(required = false) albumId: List<String>?,
        @RequestParam(required = false) artistId: List<String>?,
        @RequestParam(required = false) f: String?,
        principal: Principal,
    ): ResponseEntity<String> {
        if (id.isNullOrEmpty() && albumId.isNullOrEmpty() && artistId.isNullOrEmpty()) {
            throw SubsonicApiException(10, "Required parameter is missing: id")
        }
        val userId = UserId(principal.name)
        val requested =
            id
                .orEmpty()
                .mapNotNull { safeEntityId(it)?.value }
                .map { TrackId(it) }
                .toSet()
        val knownTracks = if (requested.isEmpty()) emptySet() else libraryQueries.trackIdsExist(requested)
        if (knownTracks.isEmpty() && albumId.isNullOrEmpty() && artistId.isNullOrEmpty() && !anyResolvesToAlbumOrArtist(requested)) {
            return notFound("Song", id.orEmpty().joinToString(), f)
        }
        for (trackId in knownTracks) {
            val ctx = preferencesContext(userId)
            val result = preferencesUseCases.execute(SetFavorite(userId, trackId, ctx.requestTime), ctx)
            if (result is CommandResult.Failed) return responseWriter.write(errorFrom(result.failure), f)
        }
        return responseWriter.write(ok(), f)
    }

    private fun anyResolvesToAlbumOrArtist(ids: Set<TrackId>): Boolean =
        ids.any { libraryQueries.getAlbum(EntityId(it.value)) != null || libraryQueries.getArtist(EntityId(it.value)) != null }

    private fun preferencesContext(userId: UserId): CommandContext {
        val prefs = preferencesQueries.find(userId)
        return ctxFactory.create(userId, prefs?.version ?: AggregateVersion.INITIAL)
    }

    @GetMapping("/unstar", "/unstar.view")
    fun unstar(
        @RequestParam(required = false) id: List<String>?,
        @RequestParam(required = false) albumId: List<String>?,
        @RequestParam(required = false) artistId: List<String>?,
        @RequestParam(required = false) f: String?,
        principal: Principal,
    ): ResponseEntity<String> {
        if (id.isNullOrEmpty() && albumId.isNullOrEmpty() && artistId.isNullOrEmpty()) {
            throw SubsonicApiException(10, "Required parameter is missing: id")
        }
        val userId = UserId(principal.name)
        for (rawId in id.orEmpty()) {
            val entityId = safeEntityId(rawId) ?: continue
            val ctx = preferencesContext(userId)
            val result = preferencesUseCases.execute(UnsetFavorite(userId, TrackId(entityId.value)), ctx)
            if (result is CommandResult.Failed && result.failure !is Failure.NotFound) {
                return responseWriter.write(errorFrom(result.failure), f)
            }
        }
        return responseWriter.write(ok(), f)
    }

    private fun favoriteChildren(userId: UserId): List<ChildElement> {
        val favorites =
            preferencesQueries
                .find(userId)
                ?.favorites
                .orEmpty()
                .sortedBy { it.position }
        if (favorites.isEmpty()) return emptyList()
        val favoritedAtByTrack = favorites.associate { it.trackId.value to it.favoritedAt }
        return libraryQueries
            .getTracksByIds(favorites.map { EntityId(it.trackId.value) })
            .toSubsonicChildren()
            .map { child -> child.copy(starred = favoritedAtByTrack[child.id]?.toString()) }
    }

    @GetMapping("/getStarred", "/getStarred.view")
    fun getStarred(
        @RequestParam(required = false) f: String?,
        principal: Principal,
    ): ResponseEntity<String> = responseWriter.write(ok { copy(starred = StarredWrapper(song = favoriteChildren(UserId(principal.name)))) }, f)

    @GetMapping("/getStarred2", "/getStarred2.view")
    fun getStarred2(
        @RequestParam(required = false) f: String?,
        principal: Principal,
    ): ResponseEntity<String> = responseWriter.write(ok { copy(starred2 = Starred2(song = favoriteChildren(UserId(principal.name)))) }, f)

    // --- Playlists ---

    @GetMapping("/getPlaylists", "/getPlaylists.view")
    fun getPlaylists(
        @RequestParam(required = false) f: String?,
        principal: Principal,
    ): ResponseEntity<String> {
        val playlists = playlistQueries.findByOwner(UserId(principal.name))
        return responseWriter.write(
            ok {
                copy(
                    playlists =
                        PlaylistsWrapper(
                            playlists.map {
                                PlaylistElement(it.id.value, it.name, it.tracks.size, it.isPublic, it.owner.value)
                            },
                        ),
                )
            },
            f,
        )
    }

    private fun playlistDetail(playlist: PlaylistAggregate): PlaylistDetail {
        val entries =
            libraryQueries
                .getTracksByIds(playlist.tracks.map { EntityId(it.trackId.value) })
                .toSubsonicChildren()
        return PlaylistDetail(
            playlist.id.value,
            playlist.name,
            entries.size,
            entries,
            playlist.isPublic,
            playlist.owner.value,
        )
    }

    @GetMapping("/getPlaylist", "/getPlaylist.view")
    fun getPlaylist(
        @RequestParam id: String,
        @RequestParam(required = false) f: String?,
    ): ResponseEntity<String> {
        val playlist = playlistQueries.find(PlaylistId(id)) ?: return notFound("Playlist", id, f)
        return responseWriter.write(ok { copy(playlist = playlistDetail(playlist)) }, f)
    }

    @GetMapping("/createPlaylist", "/createPlaylist.view")
    fun createPlaylist(
        @RequestParam(required = false) playlistId: String?,
        @RequestParam(required = false) name: String?,
        @RequestParam(required = false) songId: List<String>?,
        @RequestParam(required = false) f: String?,
        principal: Principal,
    ): ResponseEntity<String> {
        val userId = UserId(principal.name)
        val targetId =
            if (playlistId != null) {
                redefinePlaylistContent(userId, PlaylistId(playlistId), songId.orEmpty())
            } else {
                createNewPlaylist(userId, name, songId.orEmpty())
            }
        val created = playlistQueries.find(targetId) ?: return notFound("Playlist", targetId.value, f)
        return responseWriter.write(ok { copy(playlist = playlistDetail(created)) }, f)
    }

    private fun redefinePlaylistContent(
        userId: UserId,
        playlistId: PlaylistId,
        songIds: List<String>,
    ): PlaylistId {
        val playlist =
            playlistQueries.find(playlistId)
                ?: throw SubsonicApiException(70, "Playlist not found")
        var version = playlist.version
        if (playlist.tracks.isNotEmpty()) {
            version = executePlaylistCommand(userId, version) { RemovePlaylistEntriesByPosition(playlistId, playlist.tracks.indices.toList()) }
        }
        if (songIds.isNotEmpty()) {
            executePlaylistCommand(userId, version) { ctx -> AddTracksToPlaylist(playlistId, songIds.map { TrackId(it) }, ctx.requestTime) }
        }
        return playlistId
    }

    private fun createNewPlaylist(
        userId: UserId,
        name: String?,
        songIds: List<String>,
    ): PlaylistId {
        if (name == null) throw SubsonicApiException(10, "Required parameter is missing: name")
        val newId = PlaylistId(UUID.randomUUID().toString())
        val version =
            executePlaylistCommand(userId, AggregateVersion.INITIAL) { ctx ->
                CreatePlaylist(newId, userId, name, null, false, ctx.requestTime)
            }
        if (songIds.isNotEmpty()) {
            executePlaylistCommand(userId, version) { ctx -> AddTracksToPlaylist(newId, songIds.map { TrackId(it) }, ctx.requestTime) }
        }
        return newId
    }

    private fun executePlaylistCommand(
        userId: UserId,
        expectedVersion: AggregateVersion,
        command: (CommandContext) -> PlaylistCommand,
    ): AggregateVersion {
        val ctx = ctxFactory.create(userId, expectedVersion)
        return when (val result = playlistUseCases.execute(command(ctx), ctx)) {
            is CommandResult.Success -> result.newVersion
            is CommandResult.Failed -> {
                val payload = failureTranslator.translate(result.failure)
                throw SubsonicApiException(payload.code, payload.message)
            }
        }
    }

    @GetMapping("/updatePlaylist", "/updatePlaylist.view")
    fun updatePlaylist(
        @RequestParam playlistId: String,
        @RequestParam(required = false) name: String?,
        @RequestParam(required = false) comment: String?,
        @RequestParam(name = "public", required = false) isPublic: Boolean?,
        @RequestParam(required = false) songIdToAdd: List<String>?,
        @RequestParam(required = false) songIndexToRemove: List<Int>?,
        @RequestParam(required = false) f: String?,
        principal: Principal,
    ): ResponseEntity<String> {
        val pid = PlaylistId(playlistId)
        val playlist = playlistQueries.find(pid) ?: return notFound("Playlist", playlistId, f)
        val userId = UserId(principal.name)
        var version = playlist.version
        val steps =
            buildList<(CommandContext) -> PlaylistCommand> {
                name?.let { newName -> add { RenamePlaylist(pid, newName) } }
                comment?.let { newComment -> add { UpdatePlaylistDescription(pid, newComment) } }
                isPublic?.let { visibility -> add { SetPlaylistVisibility(pid, visibility) } }
                songIndexToRemove?.takeIf { it.isNotEmpty() }?.let { positions -> add { RemovePlaylistEntriesByPosition(pid, positions) } }
                songIdToAdd?.takeIf { it.isNotEmpty() }?.let { ids ->
                    add { ctx -> AddTracksToPlaylist(pid, ids.map { TrackId(it) }, ctx.requestTime) }
                }
            }
        for (step in steps) {
            version = executePlaylistCommand(userId, version, step)
        }
        return responseWriter.write(ok(), f)
    }

    @GetMapping("/deletePlaylist", "/deletePlaylist.view")
    fun deletePlaylist(
        @RequestParam id: String,
        @RequestParam(required = false) f: String?,
        principal: Principal,
    ): ResponseEntity<String> {
        val playlist = playlistQueries.find(PlaylistId(id)) ?: return notFound("Playlist", id, f)
        val ctx = ctxFactory.create(UserId(principal.name), playlist.version)
        val result = playlistUseCases.execute(DeletePlaylist(PlaylistId(id)), ctx)
        return responseWriter.write(responseFor(result), f)
    }

    // --- Playback ---

    @GetMapping("/scrobble", "/scrobble.view")
    fun scrobble(
        @RequestParam(required = false) id: List<String>?,
        @RequestParam(required = false) time: List<Long>?,
        @RequestParam(defaultValue = "true") submission: Boolean,
        @RequestParam(required = false) f: String?,
        principal: Principal,
    ): ResponseEntity<String> {
        if (id.isNullOrEmpty()) throw SubsonicApiException(10, "Required parameter is missing: id")
        if (!submission) return responseWriter.write(ok(), f)
        val userId = UserId(principal.name)
        id.forEachIndexed { index, rawId ->
            val entityId = safeEntityId(rawId) ?: return@forEachIndexed
            val track = libraryQueries.getTrack(entityId) ?: return@forEachIndexed
            val durationMs = track.durationMs ?: 0L
            val startedAt = time?.getOrNull(index)?.let { Instant.ofEpochMilli(it) } ?: clock.now().minusMillis(durationMs)
            scrobbleService.recordScrobble(
                userId = userId,
                trackId = TrackId(entityId.value),
                startedAt = startedAt,
                stoppedAt = startedAt.plusMillis(durationMs),
                positionMs = durationMs,
            )
        }
        return responseWriter.write(ok(), f)
    }

    @GetMapping("/getNowPlaying", "/getNowPlaying.view")
    fun getNowPlaying(
        @RequestParam(required = false) f: String?,
    ): ResponseEntity<String> = responseWriter.write(ok { copy(nowPlaying = NowPlayingWrapper()) }, f)

    // --- Fallback & errors ---

    @RequestMapping("/**")
    fun unknownEndpoint(
        @RequestParam(required = false) f: String?,
    ): ResponseEntity<String> = responseWriter.write(error(70, "Endpoint not found"), f)

    @ExceptionHandler(SubsonicApiException::class)
    fun handleSubsonicApiException(
        e: SubsonicApiException,
        request: HttpServletRequest,
    ): ResponseEntity<String> = responseWriter.write(error(e.code, e.message), request.getParameter("f"))

    @ExceptionHandler(MissingServletRequestParameterException::class)
    fun handleMissingParameter(
        e: MissingServletRequestParameterException,
        request: HttpServletRequest,
    ): ResponseEntity<String> = responseWriter.write(error(10, "Required parameter is missing: ${e.parameterName}"), request.getParameter("f"))

    @ExceptionHandler(Exception::class)
    fun handleUnexpected(
        e: Exception,
        request: HttpServletRequest,
    ): ResponseEntity<String> {
        log.error("Unhandled Subsonic API error on {}", request.requestURI, e)
        return responseWriter.write(error(0, "Internal error"), request.getParameter("f"))
    }

    // --- Mappers ---

    private fun List<Album>.toAlbumElements(): List<AlbumElement> {
        val artistNames = libraryQueries.getEntityNamesByIds(mapNotNull { it.artistId }.toSet())
        return map { album ->
            AlbumElement(
                id = album.id.value,
                name = album.name,
                artist = album.artistId?.let { artistNames[it] },
                artistId = album.artistId?.value,
                year = album.releaseDate?.year,
                songCount = album.totalTracks,
                coverArt = album.coverImagePath?.let { album.id.value },
            )
        }
    }

    private fun Album.toDirectoryChild(artistName: String?): ChildElement =
        ChildElement(
            id = id.value,
            parent = artistId?.value,
            title = name,
            artist = artistName,
            artistId = artistId?.value,
            year = releaseDate?.year,
            coverArt = coverImagePath?.let { id.value },
            isDir = true,
        )
}
