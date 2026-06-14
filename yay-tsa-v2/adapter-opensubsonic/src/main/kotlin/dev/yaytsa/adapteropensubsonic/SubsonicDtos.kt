package dev.yaytsa.adapteropensubsonic

import dev.yaytsa.adaptershared.ChildElement

data class ArtistsWrapper(
    val ignoredArticles: String = "",
    val index: List<ArtistIndex>,
)

data class IndexesWrapper(
    val lastModified: Long = 0,
    val ignoredArticles: String = "",
    val index: List<ArtistIndex>,
)

data class DirectoryWrapper(
    val id: String,
    val parent: String? = null,
    val name: String,
    val child: List<ChildElement> = emptyList(),
)

data class ArtistIndex(
    val name: String,
    val artist: List<ArtistElement>,
)

data class ArtistElement(
    val id: String,
    val name: String,
    val albumCount: Int? = null,
    val coverArt: String? = null,
)

data class ArtistDetail(
    val id: String,
    val name: String,
    val album: List<AlbumElement> = emptyList(),
)

data class AlbumElement(
    val id: String,
    val name: String,
    val artist: String? = null,
    val artistId: String? = null,
    val year: Int? = null,
    val songCount: Int? = null,
    val duration: Int? = null,
    val coverArt: String? = null,
)

data class AlbumDetail(
    val id: String,
    val name: String,
    val artist: String? = null,
    val artistId: String? = null,
    val year: Int? = null,
    val song: List<ChildElement> = emptyList(),
    val coverArt: String? = null,
)

data class AlbumListWrapper(
    val album: List<AlbumElement>,
)

data class AlbumListV1Wrapper(
    val album: List<ChildElement>,
)

data class StarredWrapper(
    val artist: List<ArtistElement> = emptyList(),
    val album: List<ChildElement> = emptyList(),
    val song: List<ChildElement> = emptyList(),
)

data class NowPlayingWrapper(
    val entry: List<ChildElement> = emptyList(),
)

data class PlayQueueWrapper(
    val current: String? = null,
    val position: Long? = null,
    val changed: String? = null,
    val changedBy: String? = null,
    val username: String? = null,
    val entry: List<ChildElement> = emptyList(),
)

data class SearchResult3(
    val artist: List<ArtistElement> = emptyList(),
    val album: List<AlbumElement> = emptyList(),
    val song: List<ChildElement> = emptyList(),
)

data class PlaylistsWrapper(
    val playlist: List<PlaylistElement>,
)

data class PlaylistElement(
    val id: String,
    val name: String,
    val songCount: Int = 0,
    @get:com.fasterxml.jackson.annotation.JsonProperty("public") val isPublic: Boolean = false,
    val owner: String? = null,
)

data class PlaylistDetail(
    val id: String,
    val name: String,
    val songCount: Int = 0,
    val entry: List<ChildElement> = emptyList(),
    @get:com.fasterxml.jackson.annotation.JsonProperty("public") val isPublic: Boolean = false,
    val owner: String? = null,
)

data class Starred2(
    val artist: List<ArtistElement> = emptyList(),
    val album: List<AlbumElement> = emptyList(),
    val song: List<ChildElement> = emptyList(),
)

data class MusicFoldersWrapper(
    val musicFolder: List<MusicFolderElement>,
)

data class MusicFolderElement(
    val id: String,
    val name: String,
)

data class GenresWrapper(
    val genre: List<GenreElement>,
)

data class GenreElement(
    val value: String,
    val songCount: Int = 0,
    val albumCount: Int = 0,
)

data class UserDetail(
    val username: String,
    val adminRole: Boolean = false,
    val streamRole: Boolean = true,
    val downloadRole: Boolean = true,
    val scrobbleRole: Boolean = true,
)

data class LicenseDetail(
    val valid: Boolean = true,
)

data class OpenSubsonicExtension(
    val name: String,
    val versions: List<Int>,
)

data class LyricsListWrapper(
    val structuredLyrics: List<StructuredLyrics> = emptyList(),
)

data class StructuredLyrics(
    val displayArtist: String? = null,
    val displayTitle: String? = null,
    val lang: String = "xxx",
    val synced: Boolean = false,
    val offset: Int = 0,
    val line: List<LyricsLineElement> = emptyList(),
)

data class LyricsLineElement(
    val start: Long? = null,
    val value: String,
)
