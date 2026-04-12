package com.yaytsa.server.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.OffsetDateTime;
import java.util.List;

public record UserResponse(
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
    @JsonProperty("Policy") UserPolicy policy,
    @JsonProperty("Configuration") UserConfiguration configuration) {

  public record UserPolicy(
      @JsonProperty("IsAdministrator") boolean isAdministrator,
      @JsonProperty("IsHidden") boolean isHidden,
      @JsonProperty("EnableCollectionManagement") boolean enableCollectionManagement,
      @JsonProperty("EnableSubtitleManagement") boolean enableSubtitleManagement,
      @JsonProperty("EnableLyricManagement") boolean enableLyricManagement,
      @JsonProperty("IsDisabled") boolean isDisabled,
      @JsonProperty("BlockedTags") List<String> blockedTags,
      @JsonProperty("AllowedTags") List<String> allowedTags,
      @JsonProperty("EnableUserPreferenceAccess") boolean enableUserPreferenceAccess,
      @JsonProperty("AccessSchedules") List<Object> accessSchedules,
      @JsonProperty("BlockUnratedItems") List<String> blockUnratedItems,
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
      @JsonProperty("EnableContentDeletionFromFolders") List<String> enableContentDeletionFromFolders,
      @JsonProperty("EnableContentDownloading") boolean enableContentDownloading,
      @JsonProperty("EnableSyncTranscoding") boolean enableSyncTranscoding,
      @JsonProperty("EnableMediaConversion") boolean enableMediaConversion,
      @JsonProperty("EnabledDevices") List<String> enabledDevices,
      @JsonProperty("EnableAllDevices") boolean enableAllDevices,
      @JsonProperty("EnabledChannels") List<String> enabledChannels,
      @JsonProperty("EnableAllChannels") boolean enableAllChannels,
      @JsonProperty("EnabledFolders") List<String> enabledFolders,
      @JsonProperty("EnableAllFolders") boolean enableAllFolders,
      @JsonProperty("InvalidLoginAttemptCount") int invalidLoginAttemptCount,
      @JsonProperty("LoginAttemptsBeforeLockout") int loginAttemptsBeforeLockout,
      @JsonProperty("MaxActiveSessions") int maxActiveSessions,
      @JsonProperty("EnablePublicSharing") boolean enablePublicSharing,
      @JsonProperty("BlockedMediaFolders") List<String> blockedMediaFolders,
      @JsonProperty("BlockedChannels") List<String> blockedChannels,
      @JsonProperty("RemoteClientBitrateLimit") int remoteClientBitrateLimit,
      @JsonProperty("AuthenticationProviderId") String authenticationProviderId,
      @JsonProperty("PasswordResetProviderId") String passwordResetProviderId,
      @JsonProperty("SyncPlayAccess") String syncPlayAccess) {}

  public record UserConfiguration(
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
      @JsonProperty("EnableNextEpisodeAutoPlay") boolean enableNextEpisodeAutoPlay) {}

  public static UserResponse minimal(String id, String name, String serverId) {
    return new UserResponse(
        name,
        serverId,
        "Yay-Tsa Media Server",
        id,
        null, // primaryImageTag
        true, // hasPassword
        true, // hasConfiguredPassword
        false, // hasConfiguredEasyPassword
        false, // enableAutoLogin
        null, // lastLoginDate
        null, // lastActivityDate
        defaultPolicy(),
        defaultConfiguration());
  }

  private static UserPolicy defaultPolicy() {
    return jellyfinPolicy(false, false, false);
  }

  static UserPolicy jellyfinPolicy(boolean isAdmin, boolean isDisabled, boolean enableContentDeletion) {
    return new UserPolicy(
        isAdmin,
        false, // isHidden
        false, // enableCollectionManagement
        false, // enableSubtitleManagement
        false, // enableLyricManagement
        isDisabled,
        List.of(), // blockedTags
        List.of(), // allowedTags
        true, // enableUserPreferenceAccess
        List.of(), // accessSchedules
        List.of(), // blockUnratedItems
        isAdmin, // enableRemoteControlOfOtherUsers
        true, // enableSharedDeviceControl
        true, // enableRemoteAccess
        false, // enableLiveTvManagement
        true, // enableLiveTvAccess
        true, // enableMediaPlayback
        true, // enableAudioPlaybackTranscoding
        true, // enableVideoPlaybackTranscoding
        true, // enablePlaybackRemuxing
        false, // forceRemoteSourceTranscoding
        enableContentDeletion,
        List.of(), // enableContentDeletionFromFolders
        true, // enableContentDownloading
        true, // enableSyncTranscoding
        false, // enableMediaConversion
        List.of(), // enabledDevices
        true, // enableAllDevices
        List.of(), // enabledChannels
        true, // enableAllChannels
        List.of(), // enabledFolders
        true, // enableAllFolders
        0, // invalidLoginAttemptCount
        -1, // loginAttemptsBeforeLockout
        0, // maxActiveSessions (0 = unlimited)
        false, // enablePublicSharing
        List.of(), // blockedMediaFolders
        List.of(), // blockedChannels
        0, // remoteClientBitrateLimit
        "YayTsa.Server.Auth.DefaultAuthenticationProvider",
        "YayTsa.Server.Auth.DefaultPasswordResetProvider",
        "CreateAndJoinGroups");
  }

  private static UserConfiguration defaultConfiguration() {
    return new UserConfiguration(
        "", // audioLanguagePreference
        true, // playDefaultAudioTrack
        "", // subtitleLanguagePreference
        false, // displayMissingEpisodes
        new String[] {}, // groupedFolders
        "Default", // subtitleMode
        false, // displayCollectionsView
        false, // enableLocalPassword
        new String[] {}, // orderedViews
        new String[] {}, // latestItemsExcludes
        new String[] {}, // myMediaExcludes
        true, // hidePlayedInLatest
        true, // rememberAudioSelections
        true, // rememberSubtitleSelections
        true // enableNextEpisodeAutoPlay
        );
  }
}
