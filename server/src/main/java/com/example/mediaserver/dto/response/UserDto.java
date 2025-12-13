package com.example.mediaserver.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.OffsetDateTime;

/**
 * User data transfer object matching Jellyfin API specification.
 * Field names use PascalCase to match Jellyfin's JSON format.
 */
public record UserDto(
    @JsonProperty("Name") String name,
    @JsonProperty("ServerId") String serverId,
    @JsonProperty("ServerName") String serverName,
    @JsonProperty("Id") String id,
    @JsonProperty("PrimaryImageTag") String primaryImageTag,
    @JsonProperty("HasPassword") boolean hasPassword,
    @JsonProperty("HasConfiguredPassword") boolean hasConfiguredPassword,
    @JsonProperty("HasConfiguredEasyPassword") boolean hasConfiguredEasyPassword,
    @JsonProperty("EnableAutoLogin") Boolean enableAutoLogin,
    @JsonProperty("LastLoginDate") OffsetDateTime lastLoginDate,
    @JsonProperty("LastActivityDate") OffsetDateTime lastActivityDate,
    @JsonProperty("Policy") UserPolicyDto policy,
    @JsonProperty("Configuration") UserConfigurationDto configuration
) {

    /**
     * User policy settings
     */
    public record UserPolicyDto(
        @JsonProperty("IsAdministrator") boolean isAdministrator,
        @JsonProperty("IsHidden") boolean isHidden,
        @JsonProperty("IsDisabled") boolean isDisabled,
        @JsonProperty("EnableUserPreferenceAccess") boolean enableUserPreferenceAccess,
        @JsonProperty("EnableRemoteControlOfOtherUsers") boolean enableRemoteControlOfOtherUsers,
        @JsonProperty("EnableSharedDeviceControl") boolean enableSharedDeviceControl,
        @JsonProperty("EnableRemoteAccess") boolean enableRemoteAccess,
        @JsonProperty("EnableLiveTvManagement") boolean enableLiveTvManagement,
        @JsonProperty("EnableLiveTvAccess") boolean enableLiveTvAccess,
        @JsonProperty("EnableMediaPlayback") boolean enableMediaPlayback,
        @JsonProperty("EnableAudioPlaybackTranscoding") boolean enableAudioPlaybackTranscoding,
        @JsonProperty("EnableVideoPlaybackTranscoding") boolean enableVideoPlaybackTranscoding,
        @JsonProperty("EnablePlaybackRemuxing") boolean enablePlaybackRemuxing,
        @JsonProperty("ForceRemoteSourceTranscoding") boolean forceRemoteSourceTranscoding,
        @JsonProperty("EnableContentDeletion") boolean enableContentDeletion,
        @JsonProperty("EnableContentDownloading") boolean enableContentDownloading,
        @JsonProperty("EnableSyncTranscoding") boolean enableSyncTranscoding,
        @JsonProperty("EnableMediaConversion") boolean enableMediaConversion,
        @JsonProperty("EnableAllDevices") boolean enableAllDevices,
        @JsonProperty("EnableAllChannels") boolean enableAllChannels,
        @JsonProperty("EnableAllFolders") boolean enableAllFolders,
        @JsonProperty("InvalidLoginAttemptCount") int invalidLoginAttemptCount,
        @JsonProperty("LoginAttemptsBeforeLockout") int loginAttemptsBeforeLockout,
        @JsonProperty("MaxActiveSessions") int maxActiveSessions,
        @JsonProperty("AuthenticationProviderId") String authenticationProviderId,
        @JsonProperty("PasswordResetProviderId") String passwordResetProviderId,
        @JsonProperty("SyncPlayAccess") String syncPlayAccess
    ) {}

    /**
     * User configuration preferences
     */
    public record UserConfigurationDto(
        @JsonProperty("AudioLanguagePreference") String audioLanguagePreference,
        @JsonProperty("PlayDefaultAudioTrack") boolean playDefaultAudioTrack,
        @JsonProperty("SubtitleLanguagePreference") String subtitleLanguagePreference,
        @JsonProperty("DisplayMissingEpisodes") boolean displayMissingEpisodes,
        @JsonProperty("GroupedFolders") String[] groupedFolders,
        @JsonProperty("SubtitleMode") String subtitleMode,
        @JsonProperty("DisplayCollectionsView") boolean displayCollectionsView,
        @JsonProperty("EnableLocalPassword") boolean enableLocalPassword,
        @JsonProperty("OrderedViews") String[] orderedViews,
        @JsonProperty("LatestItemsExcludes") String[] latestItemsExcludes,
        @JsonProperty("MyMediaExcludes") String[] myMediaExcludes,
        @JsonProperty("HidePlayedInLatest") boolean hidePlayedInLatest,
        @JsonProperty("RememberAudioSelections") boolean rememberAudioSelections,
        @JsonProperty("RememberSubtitleSelections") boolean rememberSubtitleSelections,
        @JsonProperty("EnableNextEpisodeAutoPlay") boolean enableNextEpisodeAutoPlay
    ) {}

    /**
     * Create a minimal UserDto for basic authentication responses
     */
    public static UserDto minimal(String id, String name, String serverId) {
        return new UserDto(
            name,
            serverId,
            "Yaytsa Media Server",
            id,
            null, // primaryImageTag
            true, // hasPassword
            true, // hasConfiguredPassword
            false, // hasConfiguredEasyPassword
            false, // enableAutoLogin
            null, // lastLoginDate
            null, // lastActivityDate
            defaultPolicy(),
            defaultConfiguration()
        );
    }

    private static UserPolicyDto defaultPolicy() {
        return new UserPolicyDto(
            false, // isAdministrator
            false, // isHidden
            false, // isDisabled
            true, // enableUserPreferenceAccess
            false, // enableRemoteControlOfOtherUsers
            true, // enableSharedDeviceControl
            true, // enableRemoteAccess
            false, // enableLiveTvManagement
            true, // enableLiveTvAccess
            true, // enableMediaPlayback
            true, // enableAudioPlaybackTranscoding
            true, // enableVideoPlaybackTranscoding
            true, // enablePlaybackRemuxing
            false, // forceRemoteSourceTranscoding
            false, // enableContentDeletion
            true, // enableContentDownloading
            true, // enableSyncTranscoding
            false, // enableMediaConversion
            true, // enableAllDevices
            true, // enableAllChannels
            true, // enableAllFolders
            0, // invalidLoginAttemptCount
            3, // loginAttemptsBeforeLockout
            0, // maxActiveSessions (0 = unlimited)
            "Jellyfin.Server.Implementations.Users.DefaultAuthenticationProvider",
            "Jellyfin.Server.Implementations.Users.DefaultPasswordResetProvider",
            "CreateAndJoinGroups"
        );
    }

    private static UserConfigurationDto defaultConfiguration() {
        return new UserConfigurationDto(
            "", // audioLanguagePreference
            true, // playDefaultAudioTrack
            "", // subtitleLanguagePreference
            false, // displayMissingEpisodes
            new String[]{}, // groupedFolders
            "Default", // subtitleMode
            false, // displayCollectionsView
            false, // enableLocalPassword
            new String[]{}, // orderedViews
            new String[]{}, // latestItemsExcludes
            new String[]{}, // myMediaExcludes
            true, // hidePlayedInLatest
            true, // rememberAudioSelections
            true, // rememberSubtitleSelections
            true // enableNextEpisodeAutoPlay
        );
    }
}
