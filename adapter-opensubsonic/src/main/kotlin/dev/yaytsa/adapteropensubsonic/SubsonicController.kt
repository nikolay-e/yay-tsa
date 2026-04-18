package dev.yaytsa.adapteropensubsonic

import dev.yaytsa.application.auth.AuthQueries
import dev.yaytsa.application.auth.AuthUseCases
import dev.yaytsa.application.library.LibraryQueries
import dev.yaytsa.application.playlists.PlaylistQueries
import dev.yaytsa.application.playlists.PlaylistUseCases
import dev.yaytsa.application.preferences.PreferencesQueries
import dev.yaytsa.application.preferences.PreferencesUseCases
import dev.yaytsa.domain.library.Album
import dev.yaytsa.domain.library.Track
import dev.yaytsa.domain.playlists.AddTracksToPlaylist
import dev.yaytsa.domain.playlists.CreatePlaylist
import dev.yaytsa.domain.playlists.DeletePlaylist
import dev.yaytsa.domain.playlists.PlaylistId
import dev.yaytsa.domain.preferences.SetFavorite
import dev.yaytsa.domain.preferences.UnsetFavorite
import dev.yaytsa.shared.AggregateVersion
import dev.yaytsa.shared.EntityId
import dev.yaytsa.shared.TrackId
import dev.yaytsa.shared.UserId
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.security.Principal

@RestController
@RequestMapping("/rest")
class SubsonicController(
    private val libraryQueries: LibraryQueries,
    private val authQueries: AuthQueries,
    private val authUseCases: AuthUseCases,
    private val playlistQueries: PlaylistQueries,
    private val playlistUseCases: PlaylistUseCases,
    private val preferencesQueries: PreferencesQueries,
    private val preferencesUseCases: PreferencesUseCases,
    private val responseWriter: SubsonicResponseWriter,
    private val ctxFactory: SubsonicCommandContextFactory,
) {
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
        val user =
            if (username != null) {
                authQueries.findByUsername(username)
            } else {
                authQueries.findUser(UserId(principal.name))
            }
        val response =
            if (user != null) {
                ok { copy(user = UserDetail(username = user.username, adminRole = user.isAdmin)) }
            } else {
                error(70, "User not found")
            }
        return responseWriter.write(response, f)
    }

    @GetMapping("/getMusicFolders", "/getMusicFolders.view")
    fun getMusicFolders(
        @RequestParam(required = false) f: String?,
    ): ResponseEntity<String> = responseWriter.write(ok { copy(musicFolders = MusicFoldersWrapper(listOf(MusicFolderElement("1", "Music")))) }, f)

    // --- Browsing ---

    @GetMapping("/getArtists", "/getArtists.view")
    fun getArtists(
        @RequestParam(required = false) f: String?,
    ): ResponseEntity<String> {
        val grouped = libraryQueries.browseArtistsGroupedByLetter()
        val indexes =
            grouped.map { (letter, list) ->
                ArtistIndex(letter, list.map { ArtistElement(it.id.value, it.name) })
            }
        return responseWriter.write(ok { copy(artists = ArtistsWrapper(indexes)) }, f)
    }

    @GetMapping("/getArtist", "/getArtist.view")
    fun getArtist(
        @RequestParam id: String,
        @RequestParam(required = false) f: String?,
    ): ResponseEntity<String> {
        if (id.isBlank()) return responseWriter.write(error(10, "Missing parameter: id"), f)
        val artist = libraryQueries.getArtist(EntityId(id)) ?: return responseWriter.write(error(70, "Artist not found"), f)
        val albums = libraryQueries.browseAlbumsByArtist(EntityId(id))
        return responseWriter.write(
            ok {
                copy(artist = ArtistDetail(artist.id.value, artist.name, albums.map { it.toElement() }))
            },
            f,
        )
    }

    @GetMapping("/getAlbum", "/getAlbum.view")
    fun getAlbum(
        @RequestParam id: String,
        @RequestParam(required = false) f: String?,
    ): ResponseEntity<String> {
        if (id.isBlank()) return responseWriter.write(error(10, "Missing parameter: id"), f)
        val album = libraryQueries.getAlbum(EntityId(id)) ?: return responseWriter.write(error(70, "Album not found"), f)
        val tracks = libraryQueries.browseTracksByAlbum(EntityId(id))
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
                            tracks.map { it.toChild() },
                            album.coverImagePath,
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
        if (id.isBlank()) return responseWriter.write(error(10, "Missing parameter: id"), f)
        val track = libraryQueries.getTrack(EntityId(id)) ?: return responseWriter.write(error(70, "Song not found"), f)
        return responseWriter.write(ok { copy(song = track.toChild()) }, f)
    }

    @GetMapping("/getAlbumList2", "/getAlbumList2.view")
    fun getAlbumList2(
        @RequestParam type: String,
        @RequestParam(defaultValue = "10") size: Int,
        @RequestParam(defaultValue = "0") offset: Int,
        @RequestParam(required = false) f: String?,
    ): ResponseEntity<String> {
        val albums = libraryQueries.browseAlbums(size.coerceIn(1, 500), offset.coerceAtLeast(0))
        return responseWriter.write(ok { copy(albumList2 = AlbumListWrapper(albums.map { it.toElement() })) }, f)
    }

    // --- Search ---

    @GetMapping("/search3", "/search3.view")
    fun search3(
        @RequestParam query: String,
        @RequestParam(defaultValue = "20") artistCount: Int,
        @RequestParam(defaultValue = "20") albumCount: Int,
        @RequestParam(defaultValue = "20") songCount: Int,
        @RequestParam(required = false) f: String?,
    ): ResponseEntity<String> {
        if (query.isBlank()) return responseWriter.write(error(10, "Missing parameter: query"), f)
        val results = libraryQueries.searchText(query, maxOf(artistCount, albumCount, songCount), 0)
        return responseWriter.write(
            ok {
                copy(
                    searchResult3 =
                        SearchResult3(
                            artist = results.artists.take(artistCount).map { ArtistElement(it.id.value, it.name) },
                            album = results.albums.take(albumCount).map { it.toElement() },
                            song = results.tracks.take(songCount).map { it.toChild() },
                        ),
                )
            },
            f,
        )
    }

    // --- Favorites ---

    @GetMapping("/star", "/star.view")
    fun star(
        @RequestParam(required = false) id: String?,
        @RequestParam(required = false) f: String?,
        principal: Principal,
    ): ResponseEntity<String> {
        if (id.isNullOrBlank()) return responseWriter.write(error(10, "Missing parameter: id"), f)
        val userId = UserId(principal.name)
        val prefs = preferencesQueries.find(userId)
        val ctx = ctxFactory.create(userId, prefs?.version ?: AggregateVersion.INITIAL)
        preferencesUseCases.execute(SetFavorite(userId, TrackId(id), ctx.requestTime), ctx)
        return responseWriter.write(ok(), f)
    }

    @GetMapping("/unstar", "/unstar.view")
    fun unstar(
        @RequestParam(required = false) id: String?,
        @RequestParam(required = false) f: String?,
        principal: Principal,
    ): ResponseEntity<String> {
        if (id.isNullOrBlank()) return responseWriter.write(error(10, "Missing parameter: id"), f)
        val userId = UserId(principal.name)
        val prefs = preferencesQueries.find(userId)
        val ctx = ctxFactory.create(userId, prefs?.version ?: AggregateVersion.INITIAL)
        preferencesUseCases.execute(UnsetFavorite(userId, TrackId(id)), ctx)
        return responseWriter.write(ok(), f)
    }

    @GetMapping("/getStarred2", "/getStarred2.view")
    fun getStarred2(
        @RequestParam(required = false) f: String?,
        principal: Principal,
    ): ResponseEntity<String> {
        val userId = UserId(principal.name)
        val prefs = preferencesQueries.find(userId)
        val starredSongs =
            prefs?.favorites?.mapNotNull { fav ->
                libraryQueries.getTrack(EntityId(fav.trackId.value))?.toChild()
            } ?: emptyList()
        return responseWriter.write(ok { copy(starred2 = Starred2(song = starredSongs)) }, f)
    }

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

    @GetMapping("/getPlaylist", "/getPlaylist.view")
    fun getPlaylist(
        @RequestParam id: String,
        @RequestParam(required = false) f: String?,
    ): ResponseEntity<String> {
        if (id.isBlank()) return responseWriter.write(error(10, "Missing parameter: id"), f)
        val playlist =
            playlistQueries.find(PlaylistId(id))
                ?: return responseWriter.write(error(70, "Playlist not found"), f)
        val entries =
            playlist.tracks.mapNotNull { entry ->
                libraryQueries.getTrack(EntityId(entry.trackId.value))?.toChild()
            }
        return responseWriter.write(
            ok {
                copy(
                    playlist =
                        PlaylistDetail(
                            playlist.id.value,
                            playlist.name,
                            entries.size,
                            entries,
                            playlist.isPublic,
                            playlist.owner.value,
                        ),
                )
            },
            f,
        )
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
        if (playlistId != null) {
            // Update existing playlist — add songs
            val playlist =
                playlistQueries.find(PlaylistId(playlistId))
                    ?: return responseWriter.write(error(70, "Playlist not found"), f)
            if (!songId.isNullOrEmpty()) {
                val ctx = ctxFactory.create(userId, playlist.version)
                playlistUseCases.execute(
                    AddTracksToPlaylist(PlaylistId(playlistId), songId.map { TrackId(it) }, ctx.requestTime),
                    ctx,
                )
            }
        } else {
            // Create new playlist
            val plName = name ?: return responseWriter.write(error(10, "Missing parameter: name"), f)
            val newId =
                PlaylistId(
                    java.util.UUID
                        .randomUUID()
                        .toString(),
                )
            val ctx = ctxFactory.create(userId, AggregateVersion.INITIAL)
            playlistUseCases.execute(CreatePlaylist(newId, userId, plName, null, false, ctx.requestTime), ctx)
            if (!songId.isNullOrEmpty()) {
                val created = playlistQueries.find(newId)!!
                val addCtx = ctxFactory.create(userId, created.version)
                playlistUseCases.execute(
                    AddTracksToPlaylist(newId, songId.map { TrackId(it) }, addCtx.requestTime),
                    addCtx,
                )
            }
        }
        return responseWriter.write(ok(), f)
    }

    @GetMapping("/deletePlaylist", "/deletePlaylist.view")
    fun deletePlaylist(
        @RequestParam id: String,
        @RequestParam(required = false) f: String?,
        principal: Principal,
    ): ResponseEntity<String> {
        if (id.isBlank()) return responseWriter.write(error(10, "Missing parameter: id"), f)
        val playlist =
            playlistQueries.find(PlaylistId(id))
                ?: return responseWriter.write(error(70, "Playlist not found"), f)
        val ctx = ctxFactory.create(UserId(principal.name), playlist.version)
        playlistUseCases.execute(DeletePlaylist(PlaylistId(id)), ctx)
        return responseWriter.write(ok(), f)
    }

    @GetMapping("/scrobble", "/scrobble.view")
    fun scrobble(
        @RequestParam(required = false) f: String?,
    ): ResponseEntity<String> = responseWriter.write(ok(), f)

    @GetMapping("/getNowPlaying", "/getNowPlaying.view")
    fun getNowPlaying(
        @RequestParam(required = false) f: String?,
    ): ResponseEntity<String> = responseWriter.write(ok(), f)

    // --- Mappers ---

    private fun Track.toChild() =
        ChildElement(
            id = id.value,
            title = name,
            duration = durationMs?.let { (it / 1000).toInt() },
            bitRate = bitrate,
            track = trackNumber,
            discNumber = discNumber,
            year = year,
            genre = genre,
            coverArt = coverImagePath,
            albumId = albumId?.value,
            artistId = albumArtistId?.value,
        )

    private fun Album.toElement() =
        AlbumElement(
            id = id.value,
            name = name,
            artistId = artistId?.value,
            year = releaseDate?.year,
            coverArt = coverImagePath,
        )
}
