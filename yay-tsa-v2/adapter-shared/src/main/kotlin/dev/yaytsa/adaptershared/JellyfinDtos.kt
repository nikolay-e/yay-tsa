package dev.yaytsa.adaptershared

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import dev.yaytsa.shared.generated.Constants

@JsonInclude(JsonInclude.Include.NON_NULL)
data class BaseItem(
    @JsonProperty("Id") val id: String,
    @JsonProperty("Name") val name: String,
    @JsonProperty("Type") val type: String,
    @JsonProperty("Album") val album: String? = null,
    @JsonProperty("AlbumId") val albumId: String? = null,
    @JsonProperty("AlbumPrimaryImageTag") val albumPrimaryImageTag: String? = null,
    @JsonProperty("Artists") val artists: List<String>? = null,
    @JsonProperty("ArtistItems") val artistItems: List<NameIdPair>? = null,
    @JsonProperty("RunTimeTicks") val runTimeTicks: Long? = null,
    @JsonProperty("IndexNumber") val indexNumber: Int? = null,
    @JsonProperty("ParentIndexNumber") val parentIndexNumber: Int? = null,
    @JsonProperty("ImageTags") val imageTags: Map<String, String>? = null,
    @JsonProperty("UserData") val userData: UserItemData? = null,
    @JsonProperty("DateCreated") val dateCreated: String? = null,
    @JsonProperty("Genres") val genres: List<String>? = null,
    @JsonProperty("ChildCount") val childCount: Int? = null,
    @JsonProperty("TotalTracks") val totalTracks: Int? = null,
    @JsonProperty("PlaylistItemId") val playlistItemId: String? = null,
    @JsonProperty("SortName") val sortName: String? = null,
    @JsonProperty("ParentId") val parentId: String? = null,
    @JsonProperty("MediaSources") val mediaSources: List<Any>? = null,
    @JsonProperty("CollectionType") val collectionType: String? = null,
    @JsonProperty("ServerId") val serverId: String? = null,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class UserItemData(
    @JsonProperty("PlaybackPositionTicks") val playbackPositionTicks: Long = 0,
    @JsonProperty("PlayCount") val playCount: Int = 0,
    @JsonProperty("IsFavorite") val isFavorite: Boolean = false,
    @JsonProperty("Played") val played: Boolean = false,
    @JsonProperty("Key") val key: String? = null,
    @JsonProperty("ItemId") val itemId: String? = null,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class NameIdPair(
    @JsonProperty("Name") val name: String,
    @JsonProperty("Id") val id: String,
)

val TICKS_PER_MS: Long = Constants.TICKS_PER_MS

fun msToTicks(ms: Long?): Long? = ms?.let { it * TICKS_PER_MS }

fun ticksToMs(ticks: Long?): Long? = ticks?.let { it / TICKS_PER_MS }
