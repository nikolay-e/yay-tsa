package dev.yaytsa.adaptershared

import com.fasterxml.jackson.annotation.JsonInclude

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ChildElement(
    val id: String,
    val parent: String? = null,
    val title: String,
    val album: String? = null,
    val artist: String? = null,
    val albumId: String? = null,
    val artistId: String? = null,
    val duration: Int? = null,
    val bitRate: Int? = null,
    val track: Int? = null,
    val discNumber: Int? = null,
    val year: Int? = null,
    val genre: String? = null,
    val coverArt: String? = null,
    val isDir: Boolean = false,
    val isVideo: Boolean = false,
    val type: String = "music",
    val mediaType: String = "song",
    val starred: String? = null,
)
