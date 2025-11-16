package com.example.mediaserver.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Session information DTO matching Jellyfin API specification.
 */
public record SessionInfoDto(
    @JsonProperty("PlayState") PlayerStateInfo playState,
    @JsonProperty("AdditionalUsers") List<SessionUserInfo> additionalUsers,
    @JsonProperty("Capabilities") ClientCapabilitiesDto capabilities,
    @JsonProperty("RemoteEndPoint") String remoteEndPoint,
    @JsonProperty("PlayableMediaTypes") List<String> playableMediaTypes,
    @JsonProperty("Id") String id,
    @JsonProperty("UserId") String userId,
    @JsonProperty("UserName") String userName,
    @JsonProperty("Client") String client,
    @JsonProperty("LastActivityDate") OffsetDateTime lastActivityDate,
    @JsonProperty("LastPlaybackCheckIn") OffsetDateTime lastPlaybackCheckIn,
    @JsonProperty("LastPausedDate") OffsetDateTime lastPausedDate,
    @JsonProperty("DeviceName") String deviceName,
    @JsonProperty("DeviceType") String deviceType,
    @JsonProperty("NowPlayingItem") BaseItemDto nowPlayingItem,
    @JsonProperty("NowViewingItem") BaseItemDto nowViewingItem,
    @JsonProperty("DeviceId") String deviceId,
    @JsonProperty("ApplicationVersion") String applicationVersion,
    @JsonProperty("TranscodingInfo") TranscodingInfo transcodingInfo,
    @JsonProperty("IsActive") boolean isActive,
    @JsonProperty("SupportsMediaControl") boolean supportsMediaControl,
    @JsonProperty("SupportsRemoteControl") boolean supportsRemoteControl,
    @JsonProperty("NowPlayingQueue") List<QueueItem> nowPlayingQueue,
    @JsonProperty("NowPlayingQueueFullItems") List<BaseItemDto> nowPlayingQueueFullItems,
    @JsonProperty("HasCustomDeviceName") boolean hasCustomDeviceName,
    @JsonProperty("PlaylistItemId") String playlistItemId,
    @JsonProperty("ServerId") String serverId,
    @JsonProperty("UserPrimaryImageTag") String userPrimaryImageTag,
    @JsonProperty("SupportedCommands") List<String> supportedCommands
) {

    public record PlayerStateInfo(
        @JsonProperty("PositionTicks") Long positionTicks,
        @JsonProperty("CanSeek") boolean canSeek,
        @JsonProperty("IsPaused") boolean isPaused,
        @JsonProperty("IsMuted") boolean isMuted,
        @JsonProperty("VolumeLevel") Integer volumeLevel,
        @JsonProperty("AudioStreamIndex") Integer audioStreamIndex,
        @JsonProperty("SubtitleStreamIndex") Integer subtitleStreamIndex,
        @JsonProperty("MediaSourceId") String mediaSourceId,
        @JsonProperty("PlayMethod") String playMethod,
        @JsonProperty("RepeatMode") String repeatMode,
        @JsonProperty("LiveStreamId") String liveStreamId
    ) {}

    public record SessionUserInfo(
        @JsonProperty("UserId") String userId,
        @JsonProperty("UserName") String userName
    ) {}

    public record ClientCapabilitiesDto(
        @JsonProperty("PlayableMediaTypes") List<String> playableMediaTypes,
        @JsonProperty("SupportedCommands") List<String> supportedCommands,
        @JsonProperty("SupportsMediaControl") boolean supportsMediaControl,
        @JsonProperty("SupportsPersistentIdentifier") boolean supportsPersistentIdentifier,
        @JsonProperty("DeviceProfile") Object deviceProfile,
        @JsonProperty("AppStoreUrl") String appStoreUrl,
        @JsonProperty("IconUrl") String iconUrl
    ) {}

    public record TranscodingInfo(
        @JsonProperty("AudioCodec") String audioCodec,
        @JsonProperty("VideoCodec") String videoCodec,
        @JsonProperty("Container") String container,
        @JsonProperty("IsVideoDirect") boolean isVideoDirect,
        @JsonProperty("IsAudioDirect") boolean isAudioDirect,
        @JsonProperty("Bitrate") Integer bitrate,
        @JsonProperty("Framerate") Float framerate,
        @JsonProperty("CompletionPercentage") Double completionPercentage,
        @JsonProperty("Width") Integer width,
        @JsonProperty("Height") Integer height,
        @JsonProperty("AudioChannels") Integer audioChannels,
        @JsonProperty("HardwareAccelerationType") String hardwareAccelerationType,
        @JsonProperty("TranscodeReasons") List<String> transcodeReasons
    ) {}

    public record QueueItem(
        @JsonProperty("Id") String id,
        @JsonProperty("PlaylistItemId") String playlistItemId
    ) {}

    /**
     * Create a minimal SessionInfoDto for authentication response
     */
    public static SessionInfoDto forAuth(String sessionId, String userId, String userName,
                                          String deviceId, String deviceName, String client) {
        return new SessionInfoDto(
            new PlayerStateInfo(0L, true, true, false, 100, null, null, null, "DirectPlay", "RepeatNone", null),
            List.of(),
            new ClientCapabilitiesDto(
                List.of("Audio"),
                List.of("PlayState", "DisplayMessage", "PlayNext"),
                true,
                true,
                null,
                null,
                null
            ),
            null, // remoteEndPoint
            List.of("Audio"),
            sessionId,
            userId,
            userName,
            client,
            OffsetDateTime.now(),
            OffsetDateTime.now(),
            null, // lastPausedDate
            deviceName,
            "Browser",
            null, // nowPlayingItem
            null, // nowViewingItem
            deviceId,
            "0.1.0",
            null, // transcodingInfo
            true, // isActive
            true, // supportsMediaControl
            true, // supportsRemoteControl
            List.of(),
            List.of(),
            false, // hasCustomDeviceName
            null, // playlistItemId
            "yaytsa-server",
            null, // userPrimaryImageTag
            List.of("PlayState", "DisplayMessage", "PlayNext")
        );
    }
}