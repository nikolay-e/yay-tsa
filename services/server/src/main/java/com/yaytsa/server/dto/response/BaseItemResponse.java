package com.yaytsa.server.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * Base item response matching Jellyfin API specification. This represents all media items:
 * AudioTrack, MusicAlbum, MusicArtist, etc.
 *
 * <p>CRITICAL: Field names MUST use PascalCase to match Jellyfin API! CRITICAL: Time durations use
 * TICKS (10,000,000 ticks = 1 second)!
 */
public record BaseItemResponse(
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
    @JsonProperty("Lyrics") String lyrics,
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
    @JsonProperty("ChannelPrimaryImageTag") String channelPrimaryImageTag,

    // Child count (for albums: track count, for artists: album count)
    @JsonProperty("ChildCount") Integer childCount,

    // Album completion status (null for non-albums)
    @JsonProperty("IsComplete") Boolean isComplete,

    // Total tracks on the album (from metadata provider, for completion badge)
    @JsonProperty("TotalTracks") Integer totalTracks) {

  /** Name-GUID pair for artists, genres, etc. */
  public record NameGuidPair(@JsonProperty("Name") String name, @JsonProperty("Id") String id) {}

  /** User-specific item data (play state, favorite status, etc.) */
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
      @JsonProperty("ItemId") String itemId) {}

  /** Media stream information */
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
      @JsonProperty("SupportsExternalStream") Boolean supportsExternalStream) {}

  /** Media source information */
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
      @JsonProperty("DefaultSubtitleStreamIndex") Integer defaultSubtitleStreamIndex) {}

  /** Chapter information */
  public record ChapterInfoDto(
      @JsonProperty("StartPositionTicks") Long startPositionTicks,
      @JsonProperty("Name") String name,
      @JsonProperty("ImageTag") String imageTag,
      @JsonProperty("ImageDateModified") OffsetDateTime imageDateModified) {}

  public static Builder builder() {
    return new Builder();
  }

  public static class Builder {
    private String name;
    private String originalTitle;
    private String serverId = "yaytsa-server";
    private String id;
    private String etag;
    private String sourceType = "Library";
    private String playlistItemId;
    private OffsetDateTime dateCreated;
    private OffsetDateTime dateLastMediaAdded;
    private String extraType;
    private String sortName;
    private String forcedSortName;
    private OffsetDateTime premiereDate;
    private String overview;
    private String officialRating;
    private String customRating;
    private Float communityRating;
    private Float criticRating;
    private Integer indexNumber;
    private Integer parentIndexNumber;
    private Integer indexNumberEnd;
    private Long runTimeTicks;
    private String type;
    private Boolean isFolder;
    private String mediaType;
    private String collectionType;
    private String parentId;
    private String parentLogoItemId;
    private String parentBackdropItemId;
    private List<String> parentBackdropImageTags;
    private UserItemDataDto userData;
    private String albumId;
    private String albumPrimaryImageTag;
    private String albumArtist;
    private List<NameGuidPair> albumArtists;
    private List<String> artists;
    private List<NameGuidPair> artistItems;
    private String album;
    private List<String> genres;
    private List<NameGuidPair> genreItems;
    private Map<String, String> imageTags;
    private List<String> backdropImageTags;
    private Double primaryImageAspectRatio;
    private List<MediaStreamDto> mediaStreams;
    private List<MediaSourceInfoDto> mediaSources;
    private String path;
    private String container;
    private Long size;
    private Boolean canDownload = true;
    private Boolean canDelete = false;
    private Boolean hasSubtitles = false;
    private Integer productionYear;
    private String lyrics;
    private Boolean isPlaceHolder = false;
    private List<Object> remoteTrailers;
    private Map<String, String> providerIds;
    private List<String> tags;
    private String seriesName;
    private String seriesId;
    private String seasonId;
    private String seasonName;
    private String locationType = "FileSystem";
    private String isoType;
    private String video3DFormat;
    private List<ChapterInfoDto> chapters;
    private Boolean lockData = false;
    private List<String> lockedFields;
    private String channelId;
    private String channelName;
    private String channelNumber;
    private String channelPrimaryImageTag;
    private Integer childCount;
    private Boolean isComplete;
    private Integer totalTracks;

    public Builder name(String name) {
      this.name = name;
      return this;
    }

    public Builder id(String id) {
      this.id = id;
      return this;
    }

    public Builder sortName(String sortName) {
      this.sortName = sortName;
      return this;
    }

    public Builder type(String type) {
      this.type = type;
      return this;
    }

    public Builder isFolder(Boolean isFolder) {
      this.isFolder = isFolder;
      return this;
    }

    public Builder mediaType(String mediaType) {
      this.mediaType = mediaType;
      return this;
    }

    public Builder parentId(String parentId) {
      this.parentId = parentId;
      return this;
    }

    public Builder userData(UserItemDataDto userData) {
      this.userData = userData;
      return this;
    }

    public Builder albumId(String albumId) {
      this.albumId = albumId;
      return this;
    }

    public Builder albumPrimaryImageTag(String tag) {
      this.albumPrimaryImageTag = tag;
      return this;
    }

    public Builder albumArtist(String albumArtist) {
      this.albumArtist = albumArtist;
      return this;
    }

    public Builder albumArtists(List<NameGuidPair> albumArtists) {
      this.albumArtists = albumArtists;
      return this;
    }

    public Builder artists(List<String> artists) {
      this.artists = artists;
      return this;
    }

    public Builder artistItems(List<NameGuidPair> artistItems) {
      this.artistItems = artistItems;
      return this;
    }

    public Builder album(String album) {
      this.album = album;
      return this;
    }

    public Builder genres(List<String> genres) {
      this.genres = genres;
      return this;
    }

    public Builder genreItems(List<NameGuidPair> genreItems) {
      this.genreItems = genreItems;
      return this;
    }

    public Builder imageTags(Map<String, String> imageTags) {
      this.imageTags = imageTags;
      return this;
    }

    public Builder indexNumber(Integer indexNumber) {
      this.indexNumber = indexNumber;
      return this;
    }

    public Builder parentIndexNumber(Integer parentIndexNumber) {
      this.parentIndexNumber = parentIndexNumber;
      return this;
    }

    public Builder runTimeTicks(Long runTimeTicks) {
      this.runTimeTicks = runTimeTicks;
      return this;
    }

    public Builder dateCreated(OffsetDateTime dateCreated) {
      this.dateCreated = dateCreated;
      return this;
    }

    public Builder premiereDate(OffsetDateTime premiereDate) {
      this.premiereDate = premiereDate;
      return this;
    }

    public Builder overview(String overview) {
      this.overview = overview;
      return this;
    }

    public Builder productionYear(Integer productionYear) {
      this.productionYear = productionYear;
      return this;
    }

    public Builder lyrics(String lyrics) {
      this.lyrics = lyrics;
      return this;
    }

    public Builder path(String path) {
      this.path = path;
      return this;
    }

    public Builder container(String container) {
      this.container = container;
      return this;
    }

    public Builder collectionType(String collectionType) {
      this.collectionType = collectionType;
      return this;
    }

    public Builder childCount(Integer childCount) {
      this.childCount = childCount;
      return this;
    }

    public Builder isComplete(Boolean isComplete) {
      this.isComplete = isComplete;
      return this;
    }

    public Builder totalTracks(Integer totalTracks) {
      this.totalTracks = totalTracks;
      return this;
    }

    public BaseItemResponse build() {
      return new BaseItemResponse(
          name,
          originalTitle,
          serverId,
          id,
          etag,
          sourceType,
          playlistItemId,
          dateCreated,
          dateLastMediaAdded,
          extraType,
          sortName,
          forcedSortName,
          premiereDate,
          overview,
          officialRating,
          customRating,
          communityRating,
          criticRating,
          indexNumber,
          parentIndexNumber,
          indexNumberEnd,
          runTimeTicks,
          type,
          isFolder,
          mediaType,
          collectionType,
          parentId,
          parentLogoItemId,
          parentBackdropItemId,
          parentBackdropImageTags,
          userData,
          albumId,
          albumPrimaryImageTag,
          albumArtist,
          albumArtists,
          artists,
          artistItems,
          album,
          genres,
          genreItems,
          imageTags,
          backdropImageTags,
          primaryImageAspectRatio,
          mediaStreams,
          mediaSources,
          path,
          container,
          size,
          canDownload,
          canDelete,
          hasSubtitles,
          productionYear,
          lyrics,
          isPlaceHolder,
          remoteTrailers,
          providerIds,
          tags,
          seriesName,
          seriesId,
          seasonId,
          seasonName,
          locationType,
          isoType,
          video3DFormat,
          chapters,
          lockData,
          lockedFields,
          channelId,
          channelName,
          channelNumber,
          channelPrimaryImageTag,
          childCount,
          isComplete,
          totalTracks);
    }
  }

  /** Create a minimal audio track item */
  public static BaseItemResponse audioTrack(
      String id,
      String name,
      String sortName,
      String albumId,
      String albumName,
      List<NameGuidPair> albumArtists,
      Integer trackNumber,
      Integer discNumber,
      Long durationMs,
      String container,
      Map<String, String> imageTags) {
    // Convert milliseconds to ticks (CRITICAL!)
    Long runTimeTicks = durationMs != null ? durationMs * 10000L : null;

    return new BaseItemResponse(
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
        null, // lyrics
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
        null, // channelPrimaryImageTag
        null, // childCount
        null, // isComplete
        null // totalTracks
        );
  }

  /** Create a minimal album item */
  public static BaseItemResponse album(
      String id,
      String name,
      String sortName,
      List<NameGuidPair> artists,
      Integer year,
      List<String> genres,
      Map<String, String> imageTags,
      Boolean isComplete,
      Integer totalTracks) {
    return new BaseItemResponse(
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
        null, // lyrics
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
        null,
        null, // childCount
        isComplete, // isComplete
        totalTracks // totalTracks
        );
  }
}
