package dev.yaytsa.adapterjellyfin

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ItemsResult<T>(
    @JsonProperty("Items") val items: List<T>,
    @JsonProperty("TotalRecordCount") val totalRecordCount: Int,
    @JsonProperty("StartIndex") val startIndex: Int = 0,
)

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

data class AuthResponse(
    @JsonProperty("User") val user: MediaServerUser,
    @JsonProperty("SessionInfo") val sessionInfo: SessionInfo,
    @JsonProperty("AccessToken") val accessToken: String,
    @JsonProperty("ServerId") val serverId: String = "yaytsa",
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class MediaServerUser(
    @JsonProperty("Id") val id: String,
    @JsonProperty("Name") val name: String,
    @JsonProperty("ServerId") val serverId: String = "yaytsa",
    @JsonProperty("HasPassword") val hasPassword: Boolean = true,
    @JsonProperty("Policy") val policy: UserPolicy = UserPolicy(),
)

data class UserPolicy(
    @JsonProperty("IsAdministrator") val isAdministrator: Boolean = false,
    @JsonProperty("IsDisabled") val isDisabled: Boolean = false,
    @JsonProperty("EnableAllFolders") val enableAllFolders: Boolean = true,
)

data class SessionInfo(
    @JsonProperty("Id") val id: String,
    @JsonProperty("UserId") val userId: String,
    @JsonProperty("DeviceId") val deviceId: String? = null,
    @JsonProperty("DeviceName") val deviceName: String? = null,
)

data class ServerInfo(
    @JsonProperty("ServerName") val serverName: String = "Yaytsa",
    @JsonProperty("Version") val version: String = "0.1.0",
    @JsonProperty("Id") val id: String = "yaytsa",
    @JsonProperty("StartupWizardCompleted") val startupWizardCompleted: Boolean = true,
)

const val TICKS_PER_MS = 10_000L

fun msToTicks(ms: Long?): Long? = ms?.let { it * TICKS_PER_MS }

fun ticksToMs(ticks: Long?): Long? = ticks?.let { it / TICKS_PER_MS }
