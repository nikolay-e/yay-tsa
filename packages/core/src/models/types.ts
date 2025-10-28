/**
 * Domain models and types for Jellyfin Mini Client
 */

// ============================================================================
// Client Configuration
// ============================================================================

export interface ClientInfo {
  name: string;
  device: string;
  deviceId: string;
  version: string;
  token?: string;
}

export interface ServerConfig {
  serverUrl: string;
  userId?: string;
  token?: string;
}

// ============================================================================
// Authentication
// ============================================================================

export interface AuthPayload {
  Username: string;
  Pw: string; // Note: "Pw" not "Password" per Jellyfin API
}

export interface AuthResponse {
  User: JellyfinUser;
  SessionInfo: SessionInfo;
  AccessToken: string;
  ServerId: string;
}

export interface JellyfinUser {
  Name: string;
  ServerId: string;
  Id: string;
  HasPassword: boolean;
  HasConfiguredPassword: boolean;
  HasConfiguredEasyPassword: boolean;
  EnableAutoLogin?: boolean;
  LastLoginDate?: string;
  LastActivityDate?: string;
  Configuration: UserConfiguration;
  Policy: UserPolicy;
}

export interface UserConfiguration {
  AudioLanguagePreference?: string;
  PlayDefaultAudioTrack: boolean;
  SubtitleLanguagePreference?: string;
  DisplayMissingEpisodes: boolean;
  GroupedFolders: string[];
  SubtitleMode: string;
  DisplayCollectionsView: boolean;
  EnableLocalPassword: boolean;
  OrderedViews: string[];
  LatestItemsExcludes: string[];
  MyMediaExcludes: string[];
  HidePlayedInLatest: boolean;
  RememberAudioSelections: boolean;
  RememberSubtitleSelections: boolean;
  EnableNextEpisodeAutoPlay: boolean;
}

export interface UserPolicy {
  IsAdministrator: boolean;
  IsHidden: boolean;
  IsDisabled: boolean;
  EnableRemoteAccess: boolean;
  EnableMediaPlayback: boolean;
  EnableAudioPlaybackTranscoding: boolean;
  EnableVideoPlaybackTranscoding: boolean;
  EnablePlaybackRemuxing: boolean;
  EnableContentDeletion: boolean;
  EnableContentDownloading: boolean;
  EnableSyncTranscoding: boolean;
  EnableMediaConversion: boolean;
  EnableAllDevices: boolean;
  EnableAllChannels: boolean;
  EnableAllFolders: boolean;
  InvalidLoginAttemptCount: number;
  EnablePublicSharing: boolean;
  BlockedMediaFolders: string[];
  BlockedChannels: string[];
  RemoteClientBitrateLimit: number;
  AuthenticationProviderId: string;
  PasswordResetProviderId: string;
  SyncPlayAccess: string;
}

export interface SessionInfo {
  PlayState: PlayState;
  AdditionalUsers: any[];
  Capabilities: any;
  RemoteEndPoint: string;
  PlayableMediaTypes: string[];
  Id: string;
  UserId: string;
  UserName: string;
  Client: string;
  LastActivityDate: string;
  LastPlaybackCheckIn: string;
  DeviceName: string;
  DeviceId: string;
  ApplicationVersion: string;
  IsActive: boolean;
  SupportsMediaControl: boolean;
  SupportsRemoteControl: boolean;
  HasCustomDeviceName: boolean;
  ServerId: string;
  UserPrimaryImageTag?: string;
}

export interface PlayState {
  PositionTicks?: number;
  CanSeek: boolean;
  IsPaused: boolean;
  IsMuted: boolean;
  VolumeLevel?: number;
  AudioStreamIndex?: number;
  SubtitleStreamIndex?: number;
  MediaSourceId?: string;
  PlayMethod?: string;
  RepeatMode: string;
}

// ============================================================================
// Library Items
// ============================================================================

export type ItemType = 'Audio' | 'MusicAlbum' | 'MusicArtist' | 'Playlist';

export interface BaseItem {
  Name: string;
  ServerId: string;
  Id: string;
  DateCreated?: string;
  Container?: string;
  SortName?: string;
  PremiereDate?: string;
  ExternalUrls?: ExternalUrl[];
  Path?: string;
  Overview?: string;
  Taglines?: string[];
  Genres?: string[];
  CommunityRating?: number;
  RunTimeTicks?: number;
  ProductionYear?: number;
  IndexNumber?: number;
  ParentIndexNumber?: number;
  Type: string;
  Studios?: NameIdPair[];
  GenreItems?: NameIdPair[];
  ParentId?: string;
  UserData?: UserItemData;
  ImageTags?: ImageTags;
  BackdropImageTags?: string[];
  ImageBlurHashes?: ImageBlurHashes;
  LocationType?: string;
  MediaType?: string;
}

export interface AudioItem extends BaseItem {
  Type: 'Audio';
  Album?: string;
  AlbumId?: string;
  AlbumPrimaryImageTag?: string;
  Artists?: string[];
  ArtistItems?: NameIdPair[];
  MediaSources?: MediaSourceInfo[];
}

export interface MusicAlbum extends BaseItem {
  Type: 'MusicAlbum';
  Artists?: string[];
  ArtistItems?: NameIdPair[];
  ChildCount?: number;
}

export interface MusicArtist extends BaseItem {
  Type: 'MusicArtist';
  ChildCount?: number;
}

export interface NameIdPair {
  Name: string;
  Id: string;
}

export interface ExternalUrl {
  Name: string;
  Url: string;
}

export interface UserItemData {
  PlaybackPositionTicks: number;
  PlayCount: number;
  IsFavorite: boolean;
  Played: boolean;
  Key?: string;
  ItemId?: string;
}

export interface ImageTags {
  Primary?: string;
  Art?: string;
  Banner?: string;
  Logo?: string;
  Thumb?: string;
  Disc?: string;
  Box?: string;
  Screenshot?: string;
  Menu?: string;
  Chapter?: string;
  BoxRear?: string;
  Profile?: string;
}

export interface ImageBlurHashes {
  Primary?: Record<string, string>;
  Art?: Record<string, string>;
  Backdrop?: Record<string, string>;
  Banner?: Record<string, string>;
  Logo?: Record<string, string>;
  Thumb?: Record<string, string>;
  Disc?: Record<string, string>;
  Box?: Record<string, string>;
  Screenshot?: Record<string, string>;
  Menu?: Record<string, string>;
  Chapter?: Record<string, string>;
  BoxRear?: Record<string, string>;
  Profile?: Record<string, string>;
}

export interface MediaSourceInfo {
  Protocol: string;
  Id: string;
  Path: string;
  Type: string;
  Container: string;
  Size: number;
  Name: string;
  IsRemote: boolean;
  ETag?: string;
  RunTimeTicks?: number;
  ReadAtNativeFramerate: boolean;
  IgnoreDts: boolean;
  IgnoreIndex: boolean;
  GenPtsInput: boolean;
  SupportsTranscoding: boolean;
  SupportsDirectStream: boolean;
  SupportsDirectPlay: boolean;
  IsInfiniteStream: boolean;
  RequiresOpening: boolean;
  RequiresClosing: boolean;
  RequiresLooping: boolean;
  SupportsProbing: boolean;
  VideoType?: string;
  MediaStreams?: MediaStream[];
  MediaAttachments?: any[];
  Formats?: string[];
  Bitrate?: number;
  RequiredHttpHeaders?: Record<string, string>;
  DefaultAudioStreamIndex?: number;
}

export interface MediaStream {
  Codec: string;
  TimeBase?: string;
  CodecTimeBase?: string;
  Title?: string;
  VideoRange?: string;
  DisplayTitle?: string;
  NalLengthSize?: string;
  IsInterlaced: boolean;
  IsAVC?: boolean;
  BitRate?: number;
  BitDepth?: number;
  RefFrames?: number;
  IsDefault: boolean;
  IsForced: boolean;
  Height?: number;
  Width?: number;
  AverageFrameRate?: number;
  RealFrameRate?: number;
  Profile?: string;
  Type: string;
  AspectRatio?: string;
  Index: number;
  IsExternal: boolean;
  IsTextSubtitleStream: boolean;
  SupportsExternalStream: boolean;
  Protocol?: string;
  PixelFormat?: string;
  Level?: number;
  IsAnamorphic?: boolean;
  Language?: string;
  Channels?: number;
  ChannelLayout?: string;
  SampleRate?: number;
}

// ============================================================================
// Query Types
// ============================================================================

export interface ItemsQuery {
  ParentId?: string;
  IncludeItemTypes?: ItemType;
  Recursive?: boolean;
  Fields?: string[];
  SortBy?: string;
  SortOrder?: 'Ascending' | 'Descending';
  StartIndex?: number;
  Limit?: number;
  SearchTerm?: string;
  ArtistIds?: string[];
  AlbumIds?: string[];
  GenreIds?: string[];
}

export interface ItemsResult<T = BaseItem> {
  Items: T[];
  TotalRecordCount: number;
  StartIndex: number;
}

// ============================================================================
// Playback
// ============================================================================

export interface StreamConfig {
  serverUrl: string;
  token: string;
  audioCodec?: string;
  maxStreamingBitrate?: string;
  container?: string;
}

export interface PlaybackProgressInfo {
  ItemId: string;
  PositionTicks: number;
  IsPaused: boolean;
  PlayMethod?: 'DirectPlay' | 'DirectStream' | 'Transcode';
  MediaSourceId?: string;
  AudioStreamIndex?: number;
  SubtitleStreamIndex?: number;
  VolumeLevel?: number;
  IsMuted?: boolean;
}

export interface PlaybackStartInfo extends PlaybackProgressInfo {
  CanSeek: boolean;
}

export interface PlaybackStopInfo {
  ItemId: string;
  PositionTicks: number;
  MediaSourceId?: string;
}

// ============================================================================
// Player State
// ============================================================================

export type PlaybackStatus = 'idle' | 'loading' | 'playing' | 'paused' | 'stopped' | 'error';
export type RepeatMode = 'off' | 'one' | 'all';
export type ShuffleMode = 'off' | 'on';

export interface PlayerState {
  status: PlaybackStatus;
  currentItem: AudioItem | null;
  currentTime: number;
  duration: number;
  volume: number;
  muted: boolean;
  repeatMode: RepeatMode;
  shuffleMode: ShuffleMode;
  error: Error | null;
}

export interface QueueState {
  items: AudioItem[];
  currentIndex: number;
  originalOrder: AudioItem[];
  repeatMode: RepeatMode;
  shuffleMode: ShuffleMode;
}

// ============================================================================
// Errors
// ============================================================================

export class JellyfinError extends Error {
  constructor(
    message: string,
    public statusCode?: number,
    public response?: any
  ) {
    super(message);
    this.name = 'JellyfinError';
  }
}

export class AuthenticationError extends JellyfinError {
  constructor(message: string, statusCode?: number, response?: any) {
    super(message, statusCode, response);
    this.name = 'AuthenticationError';
  }
}

export class NetworkError extends JellyfinError {
  constructor(
    message: string,
    public originalError?: Error
  ) {
    super(message);
    this.name = 'NetworkError';
  }
}
