package dev.yaytsa.adapteropensubsonic

import dev.yaytsa.adaptershared.LyricsResolver
import dev.yaytsa.adaptershared.LyricsSource
import dev.yaytsa.application.library.LibraryQueries
import dev.yaytsa.application.shared.port.Clock
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/rest")
class SubsonicBrowsingController(
    private val libraryQueries: LibraryQueries,
    private val lyricsResolver: LyricsResolver,
    private val clock: Clock,
    private val support: SubsonicEndpointSupport,
) {
    private fun artistIndexes(): List<ArtistIndex> =
        libraryQueries.browseArtistsGroupedByLetter().map { (letter, list) ->
            ArtistIndex(letter, list.map { ArtistElement(it.id.value, it.name) })
        }

    @GetMapping("/getArtists", "/getArtists.view")
    fun getArtists(
        @RequestParam(required = false) f: String?,
    ): ResponseEntity<String> = support.write(ok { copy(artists = ArtistsWrapper(index = artistIndexes())) }, f)

    @GetMapping("/getIndexes", "/getIndexes.view")
    fun getIndexes(
        @RequestParam(required = false) f: String?,
    ): ResponseEntity<String> =
        support.write(
            ok { copy(indexes = IndexesWrapper(lastModified = clock.now().toEpochMilli(), index = artistIndexes())) },
            f,
        )

    @GetMapping("/getMusicDirectory", "/getMusicDirectory.view")
    fun getMusicDirectory(
        @RequestParam id: String,
        @RequestParam(required = false) f: String?,
    ): ResponseEntity<String> {
        val entityId = support.safeEntityId(id) ?: return support.notFound("Directory", id, f)
        libraryQueries.getArtist(entityId)?.let { artist ->
            val children = libraryQueries.browseAlbumsByArtist(entityId).map { support.toDirectoryChild(it, artist.name) }
            return support.write(
                ok { copy(directory = DirectoryWrapper(id = artist.id.value, name = artist.name, child = children)) },
                f,
            )
        }
        libraryQueries.getAlbum(entityId)?.let { album ->
            val children = support.toChildren(libraryQueries.browseTracksByAlbum(entityId))
            return support.write(
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
        return support.notFound("Directory", id, f)
    }

    @GetMapping("/getArtist", "/getArtist.view")
    fun getArtist(
        @RequestParam id: String,
        @RequestParam(required = false) f: String?,
    ): ResponseEntity<String> {
        val entityId = support.safeEntityId(id) ?: return support.notFound("Artist", id, f)
        val artist = libraryQueries.getArtist(entityId) ?: return support.notFound("Artist", id, f)
        val albums = libraryQueries.browseAlbumsByArtist(entityId)
        return support.write(
            ok {
                copy(artist = ArtistDetail(artist.id.value, artist.name, support.toAlbumElements(albums)))
            },
            f,
        )
    }

    @GetMapping("/getAlbum", "/getAlbum.view")
    fun getAlbum(
        @RequestParam id: String,
        @RequestParam(required = false) f: String?,
    ): ResponseEntity<String> {
        val entityId = support.safeEntityId(id) ?: return support.notFound("Album", id, f)
        val album = libraryQueries.getAlbum(entityId) ?: return support.notFound("Album", id, f)
        val tracks = libraryQueries.browseTracksByAlbum(entityId)
        val artist = album.artistId?.let { libraryQueries.getArtist(it) }
        return support.write(
            ok {
                copy(
                    album =
                        AlbumDetail(
                            album.id.value,
                            album.name,
                            artist?.name,
                            artist?.id?.value,
                            album.releaseDate?.year,
                            support.toChildren(tracks),
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
        val entityId = support.safeEntityId(id) ?: return support.notFound("Song", id, f)
        val track = libraryQueries.getTrack(entityId) ?: return support.notFound("Song", id, f)
        return support.write(ok { copy(song = support.toChild(track)) }, f)
    }

    @GetMapping("/getGenres", "/getGenres.view")
    fun getGenres(
        @RequestParam(required = false) f: String?,
    ): ResponseEntity<String> {
        val genreElements =
            libraryQueries.listGenreStatistics().map {
                GenreElement(value = it.name, songCount = it.songCount, albumCount = it.albumCount)
            }
        return support.write(ok { copy(genres = GenresWrapper(genre = genreElements)) }, f)
    }

    @GetMapping("/getLyricsBySongId", "/getLyricsBySongId.view")
    fun getLyricsBySongId(
        @RequestParam id: String,
        @RequestParam(required = false) f: String?,
    ): ResponseEntity<String> {
        val entityId = support.safeEntityId(id) ?: return support.notFound("Song", id, f)
        if (libraryQueries.getTrack(entityId) == null) return support.notFound("Song", id, f)
        val resolved = lyricsResolver.resolve(entityId.value)
        val structured =
            if (resolved.source == LyricsSource.NONE) {
                emptyList()
            } else {
                listOf(
                    StructuredLyrics(
                        displayArtist = resolved.displayArtist,
                        displayTitle = resolved.displayTitle,
                        synced = resolved.synced,
                        line =
                            lyricsResolver.parseLines(resolved).map { line ->
                                LyricsLineElement(start = line.start, value = line.value)
                            },
                    ),
                )
            }
        return support.write(ok { copy(lyricsList = LyricsListWrapper(structuredLyrics = structured)) }, f)
    }
}
