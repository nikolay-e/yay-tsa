package dev.yaytsa.adapteropensubsonic

import dev.yaytsa.application.library.LibraryQueries
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/rest")
class SubsonicSearchController(
    private val libraryQueries: LibraryQueries,
    private val support: SubsonicEndpointSupport,
) {
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
                    album = takeIfPositive(albumCount) { support.toAlbumElements(libraryQueries.browseAlbums(albumCount, albumOffset)) },
                    song =
                        takeIfPositive(songCount) {
                            support.toChildren(libraryQueries.browseTracks(songCount, songOffset, "SortName", "Ascending"))
                        },
                )
            } else {
                SearchResult3(
                    artist =
                        takeIfPositive(artistCount) {
                            libraryQueries.searchText(q, artistCount, artistOffset).artists.map { ArtistElement(it.id.value, it.name) }
                        },
                    album = takeIfPositive(albumCount) { support.toAlbumElements(libraryQueries.searchText(q, albumCount, albumOffset).albums) },
                    song = takeIfPositive(songCount) { support.toChildren(libraryQueries.searchText(q, songCount, songOffset).tracks) },
                )
            }
        return support.write(ok { copy(searchResult3 = result) }, f)
    }

    private fun <T> takeIfPositive(
        count: Int,
        block: () -> List<T>,
    ): List<T> = if (count > 0) block() else emptyList()
}
