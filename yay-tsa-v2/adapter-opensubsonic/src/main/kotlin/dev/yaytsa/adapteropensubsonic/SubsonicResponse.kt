package dev.yaytsa.adapteropensubsonic

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty

@JsonInclude(JsonInclude.Include.NON_NULL)
data class SubsonicResponse(
    @JsonProperty("subsonic-response")
    val subsonicResponse: SubsonicBody,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class SubsonicBody(
    val status: String = "ok",
    val version: String = "1.16.1",
    val type: String = "Yaytsa",
    val serverVersion: String = "0.1.0",
    val openSubsonic: Boolean = true,
    // payload fields embedded here via subtypes
    val error: SubsonicError? = null,
    val artists: ArtistsWrapper? = null,
    val artist: ArtistDetail? = null,
    val album: AlbumDetail? = null,
    val song: ChildElement? = null,
    val albumList2: AlbumListWrapper? = null,
    val searchResult3: SearchResult3? = null,
    val playlists: PlaylistsWrapper? = null,
    val playlist: PlaylistDetail? = null,
    val starred2: Starred2? = null,
    val musicFolders: MusicFoldersWrapper? = null,
    val genres: GenresWrapper? = null,
    val user: UserDetail? = null,
    val license: LicenseDetail? = null,
    val openSubsonicExtensions: List<Any>? = null,
)

data class SubsonicError(
    val code: Int,
    val message: String,
)

fun ok(block: SubsonicBody.() -> SubsonicBody = { this }): SubsonicResponse = SubsonicResponse(SubsonicBody().block())

fun error(
    code: Int,
    message: String,
): SubsonicResponse = SubsonicResponse(SubsonicBody(status = "failed", error = SubsonicError(code, message)))
