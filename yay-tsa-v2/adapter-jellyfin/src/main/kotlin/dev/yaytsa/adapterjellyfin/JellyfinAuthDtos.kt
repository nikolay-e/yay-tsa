package dev.yaytsa.adapterjellyfin

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ItemsResult<T>(
    @JsonProperty("Items") val items: List<T>,
    @JsonProperty("TotalRecordCount") val totalRecordCount: Int,
    @JsonProperty("StartIndex") val startIndex: Int = 0,
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
    @JsonProperty("BuildSha") val buildSha: String = "unknown",
)
