package com.example.mediaserver.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * Base item DTO matching Jellyfin API specification.
 * This represents all media items: AudioTrack, MusicAlbum, MusicArtist, etc.
 *
 * CRITICAL: Field names MUST use PascalCase to match Jellyfin API!
 * CRITICAL: Time durations use TICKS (10,000,000 ticks = 1 second)!
 */
public record BaseItemDto(
    // Core identification
    @JsonProperty("Name") String name,
    @JsonProperty("OriginalTitle") String originalTitle,
    @JsonProperty("ServerId") String serverId,
    @JsonProperty("Id") String id,
    @JsonProperty("Etag") String etag,
    @JsonProperty("SourceType") String sourceType,
    @JsonProperty("PlaylistItemId") String playlistItemId,
    @JsonProperty("DateCreated") OffsetDateTime dateCreated,
    @JsonProperty("DateLastMediaAdded") OffsetDateTime dateLastMediaAdded,
    @JsonProperty("ExtraType") String extraType,

    // Sorting and display
    @JsonProperty("SortName") String sortName,
    @JsonProperty("ForcedSortName") String forcedSortName,
    @JsonProperty("PremiereDate") OffsetDateTime premiereDate,
    @JsonProperty("Overview") String overview,
    @JsonProperty("OfficialRating") String officialRating,
    @JsonProperty("CustomRating") String customRating,
    @JsonProperty("CommunityRating") Float communityRating,
    @JsonProperty("CriticRating") Float criticRating,

    // Track/Episode numbering (CRITICAL for audio)
    @JsonProperty("IndexNumber") Integer indexNumber, // Track number
    @JsonProperty("ParentIndexNumber") Integer parentIndexNumber, // Disc number
    @JsonProperty("IndexNumberEnd") Integer indexNumberEnd,

    // Duration (CRITICAL: in TICKS, not milliseconds!)
    @JsonProperty("RunTimeTicks") Long runTimeTicks, // 10,000,000 ticks = 1 second

    // Media type
    @JsonProperty("Type") String type, // "Audio", "MusicAlbum", "MusicArtist"
    @JsonProperty("IsFolder") Boolean isFolder,
    @JsonProperty("MediaType") String mediaType, // "Audio"
    @JsonProperty("CollectionType") String collectionType,

    // Parent references
    @JsonProperty("ParentId") String parentId,
    @JsonProperty("ParentLogoItemId") String parentLogoItemId,
    @JsonProperty("ParentBackdropItemId") String parentBackdropItemId,
    @JsonProperty("ParentBackdropImageTags") List<String> parentBackdropImageTags,

    // User-specific data
    @JsonProperty("UserData") UserItemDataDto userData,

    // Album/Artist references (CRITICAL for audio)
    @JsonProperty("AlbumId") String albumId,
    @JsonProperty("AlbumPrimaryImageTag") String albumPrimaryImageTag,
    @JsonProperty("AlbumArtist") String albumArtist,
    @JsonProperty("AlbumArtists") List<NameGuidPair> albumArtists,
    @JsonProperty("Artists") List<String> artists,
    @JsonProperty("ArtistItems") List<NameGuidPair> artistItems,
    @JsonProperty("Album") String album,

    // Genre information
    @JsonProperty("Genres") List<String> genres,
    @JsonProperty("GenreItems") List<NameGuidPair> genreItems,

    // Image information (CRITICAL for UI)
    @JsonProperty("ImageTags") Map<String, String> imageTags,
    @JsonProperty("BackdropImageTags") List<String> backdropImageTags,
    @JsonProperty("PrimaryImageAspectRatio") Double primaryImageAspectRatio,

    // Media streams
    @JsonProperty("MediaStreams") List<MediaStreamDto> mediaStreams,
    @JsonProperty("MediaSources") List<MediaSourceInfoDto> mediaSources,

    // Path information
    @JsonProperty("Path") String path,
    @JsonProperty("Container") String container,
    @JsonProperty("Size") Long size,

    // Playback info
    @JsonProperty("CanDownload") Boolean canDownload,
    @JsonProperty("CanDelete") Boolean canDelete,
    @JsonProperty("HasSubtitles") Boolean hasSubtitles,

    // Music-specific
    @JsonProperty("ProductionYear") Integer productionYear,
    @JsonProperty("IsPlaceHolder") Boolean isPlaceHolder,
    @JsonProperty("RemoteTrailers") List<Object> remoteTrailers,
    @JsonProperty("ProviderIds") Map<String, String> providerIds,
    @JsonProperty("Tags") List<String> tags,

    // Series/Season info (for TV, but included for completeness)
    @JsonProperty("SeriesName") String seriesName,
    @JsonProperty("SeriesId") String seriesId,
    @JsonProperty("SeasonId") String seasonId,
    @JsonProperty("SeasonName") String seasonName,

    // Location type
    @JsonProperty("LocationType") String locationType,
    @JsonProperty("IsoType") String isoType,
    @JsonProperty("Video3DFormat") String video3DFormat,

    // Chapters
    @JsonProperty("Chapters") List<ChapterInfoDto> chapters,

    // Locked fields
    @JsonProperty("LockData") Boolean lockData,
    @JsonProperty("LockedFields") List<String> lockedFields,

    // Channel info (for live TV)
    @JsonProperty("ChannelId") String channelId,
    @JsonProperty("ChannelName") String channelName,
    @JsonProperty("ChannelNumber") String channelNumber,
    @JsonProperty("ChannelPrimaryImageTag") String channelPrimaryImageTag
) {

    /**
     * Name-GUID pair for artists, genres, etc.
     */
    public record NameGuidPair(
        @JsonProperty("Name") String name,
        @JsonProperty("Id") String id
    ) {}

    /**
     * User-specific item data (play state, favorite status, etc.)
     */
    public record UserItemDataDto(
        @JsonProperty("Rating") Double rating,
        @JsonProperty("PlayedPercentage") Double playedPercentage,
        @JsonProperty("UnplayedItemCount") Integer unplayedItemCount,
        @JsonProperty("PlaybackPositionTicks") Long playbackPositionTicks,
        @JsonProperty("PlayCount") Integer playCount,
        @JsonProperty("IsFavorite") Boolean isFavorite,
        @JsonProperty("Likes") Boolean likes,
        @JsonProperty("LastPlayedDate") OffsetDateTime lastPlayedDate,
        @JsonProperty("Played") Boolean played,
        @JsonProperty("Key") String key,
        @JsonProperty("ItemId") String itemId
    ) {}

    /**
     * Media stream information
     */
    public record MediaStreamDto(
        @JsonProperty("Codec") String codec,
        @JsonProperty("CodecTag") String codecTag,
        @JsonProperty("Language") String language,
        @JsonProperty("TimeBase") String timeBase,
        @JsonProperty("Title") String title,
        @JsonProperty("DisplayTitle") String displayTitle,
        @JsonProperty("IsInterlaced") Boolean isInterlaced,
        @JsonProperty("ChannelLayout") String channelLayout,
        @JsonProperty("BitRate") Integer bitRate,
        @JsonProperty("Channels") Integer channels,
        @JsonProperty("SampleRate") Integer sampleRate,
        @JsonProperty("IsDefault") Boolean isDefault,
        @JsonProperty("IsForced") Boolean isForced,
        @JsonProperty("Type") String type,
        @JsonProperty("Index") Integer index,
        @JsonProperty("IsExternal") Boolean isExternal,
        @JsonProperty("IsTextSubtitleStream") Boolean isTextSubtitleStream,
        @JsonProperty("SupportsExternalStream") Boolean supportsExternalStream
    ) {}

    /**
     * Media source information
     */
    public record MediaSourceInfoDto(
        @JsonProperty("Protocol") String protocol,
        @JsonProperty("Id") String id,
        @JsonProperty("Path") String path,
        @JsonProperty("Type") String type,
        @JsonProperty("Container") String container,
        @JsonProperty("Size") Long size,
        @JsonProperty("Name") String name,
        @JsonProperty("IsRemote") Boolean isRemote,
        @JsonProperty("RunTimeTicks") Long runTimeTicks,
        @JsonProperty("SupportsTranscoding") Boolean supportsTranscoding,
        @JsonProperty("SupportsDirectStream") Boolean supportsDirectStream,
        @JsonProperty("SupportsDirectPlay") Boolean supportsDirectPlay,
        @JsonProperty("IsInfiniteStream") Boolean isInfiniteStream,
        @JsonProperty("RequiresOpening") Boolean requiresOpening,
        @JsonProperty("RequiresClosing") Boolean requiresClosing,
        @JsonProperty("RequiresLooping") Boolean requiresLooping,
        @JsonProperty("SupportsProbing") Boolean supportsProbing,
        @JsonProperty("MediaStreams") List<MediaStreamDto> mediaStreams,
        @JsonProperty("Formats") List<String> formats,
        @JsonProperty("Bitrate") Integer bitrate,
        @JsonProperty("DefaultAudioStreamIndex") Integer defaultAudioStreamIndex,
        @JsonProperty("DefaultSubtitleStreamIndex") Integer defaultSubtitleStreamIndex
    ) {}

    /**
     * Chapter information
     */
    public record ChapterInfoDto(
        @JsonProperty("StartPositionTicks") Long startPositionTicks,
        @JsonProperty("Name") String name,
        @JsonProperty("ImageTag") String imageTag,
        @JsonProperty("ImageDateModified") OffsetDateTime imageDateModified
    ) {}

    /**
     * Create a minimal audio track item
     */
    public static BaseItemDto audioTrack(String id, String name, String sortName,
                                         String albumId, String albumName,
                                         List<NameGuidPair> albumArtists,
                                         Integer trackNumber, Integer discNumber,
                                         Long durationMs, String container,
                                         Map<String, String> imageTags) {
        // Convert milliseconds to ticks (CRITICAL!)
        Long runTimeTicks = durationMs != null ? durationMs * 10000L : null;

        return new BaseItemDto(
            name,
            null, // originalTitle
            "yaytsa-server",
            id,
            null, // etag
            "Library",
            null, // playlistItemId
            null, // dateCreated
            null, // dateLastMediaAdded
            null, // extraType
            sortName != null ? sortName : name,
            null, // forcedSortName
            null, // premiereDate
            null, // overview
            null, // officialRating
            null, // customRating
            null, // communityRating
            null, // criticRating
            trackNumber, // indexNumber (track number)
            discNumber, // parentIndexNumber (disc number)
            null, // indexNumberEnd
            runTimeTicks, // TICKS!
            "Audio",
            false, // isFolder
            "Audio", // mediaType
            null, // collectionType
            albumId, // parentId
            null, // parentLogoItemId
            null, // parentBackdropItemId
            null, // parentBackdropImageTags
            null, // userData (will be filled per user)
            albumId,
            imageTags != null ? imageTags.get("Primary") : null,
            albumArtists != null && !albumArtists.isEmpty() ? albumArtists.get(0).name() : null,
            albumArtists,
            albumArtists != null ? albumArtists.stream().map(NameGuidPair::name).toList() : List.of(),
            albumArtists,
            albumName,
            List.of(), // genres
            List.of(), // genreItems
            imageTags,
            List.of(), // backdropImageTags
            1.0, // primaryImageAspectRatio
            null, // mediaStreams
            null, // mediaSources
            null, // path
            container,
            null, // size
            true, // canDownload
            false, // canDelete
            false, // hasSubtitles
            null, // productionYear
            false, // isPlaceHolder
            List.of(), // remoteTrailers
            Map.of(), // providerIds
            List.of(), // tags
            null, // seriesName
            null, // seriesId
            null, // seasonId
            null, // seasonName
            "FileSystem",
            null, // isoType
            null, // video3DFormat
            List.of(), // chapters
            false, // lockData
            List.of(), // lockedFields
            null, // channelId
            null, // channelName
            null, // channelNumber
            null // channelPrimaryImageTag
        );
    }

    /**
     * Create a minimal album item
     */
    public static BaseItemDto album(String id, String name, String sortName,
                                    List<NameGuidPair> artists,
                                    Integer year, List<String> genres,
                                    Map<String, String> imageTags) {
        return new BaseItemDto(
            name,
            null,
            "yaytsa-server",
            id,
            null,
            "Library",
            null,
            null,
            null,
            null,
            sortName != null ? sortName : name,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            "MusicAlbum",
            true, // isFolder
            "Audio",
            "music",
            null, // parentId
            null,
            null,
            null,
            null, // userData
            null, // albumId (self)
            null,
            artists != null && !artists.isEmpty() ? artists.get(0).name() : null,
            artists,
            artists != null ? artists.stream().map(NameGuidPair::name).toList() : List.of(),
            artists,
            null, // album (self)
            genres,
            genres != null ? genres.stream().map(g -> new NameGuidPair(g, g)).toList() : List.of(),
            imageTags,
            List.of(),
            1.0,
            null,
            null,
            null,
            null,
            null,
            true,
            false,
            false,
            year,
            false,
            List.of(),
            Map.of(),
            List.of(),
            null,
            null,
            null,
            null,
            "FileSystem",
            null,
            null,
            List.of(),
            false,
            List.of(),
            null,
            null,
            null,
            null
        );
    }
}
