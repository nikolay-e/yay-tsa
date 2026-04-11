/**
 * Domain models and types for Media Server Client
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

// ============================================================================
// Authentication
// ============================================================================

export interface AuthPayload {
  Username: string;
  Pw: string;
  DeviceId?: string;
  DeviceName?: string;
  ClientName?: string;
  ClientVersion?: string;
}

export interface AuthResponse {
  User: MediaServerUser;
  SessionInfo: SessionInfo;
  AccessToken: string;
  ServerId: string;
}

export interface MediaServerUser {
  Name: string;
  ServerId: string;
  Id: string;
  HasPassword: boolean;
  HasConfiguredPassword: boolean;
  HasConfiguredEasyPassword: boolean;
  EnableAutoLogin?: boolean;
  LastLoginDate?: string;
  LastActivityDate?: string;
  Configuration?: Record<string, unknown>;
  Policy: UserPolicy;
}

export interface UserPolicy {
  IsAdministrator: boolean;
}

export interface SessionUserInfo {
  Id: string;
  UserId: string;
  UserName: string;
}

export interface SessionInfo {
  PlayState: PlayState;
  AdditionalUsers: SessionUserInfo[];
  Capabilities: Record<string, unknown>;
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
export type MediaType = 'Unknown' | 'Video' | 'Audio' | 'Photo' | 'Book';

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
  ImageBlurHashes?: Record<string, Record<string, string>>;
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
  MediaSources?: unknown[];
  PlaylistItemId?: string;
  Lyrics?: string;
  NormalizationGain?: number | null;
}

export interface MusicAlbum extends BaseItem {
  Type: 'MusicAlbum';
  Artists?: string[];
  ArtistItems?: NameIdPair[];
  ChildCount?: number;
  TotalTracks?: number;
  IsComplete?: boolean;
}

export interface MusicArtist extends BaseItem {
  Type: 'MusicArtist';
  ChildCount?: number;
}

export interface Playlist extends BaseItem {
  Type: 'Playlist';
  MediaType?: string;
  IsFolder?: boolean;
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
  [key: string]: string | undefined;
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
  IsFavorite?: boolean;
  Ids?: string[];
}

export interface ItemsResult<T = BaseItem> {
  Items: T[];
  TotalRecordCount: number;
  StartIndex: number;
}

// ============================================================================
// Playlist Types
// ============================================================================

export interface CreatePlaylistDto {
  name: string;
  mediaType?: MediaType;
  itemIds?: string[];
  userId?: string;
  isPublic?: boolean;
}

// ============================================================================
// Playback
// ============================================================================

export interface PlaybackProgressInfo {
  ItemId: string;
  PositionTicks: number;
  IsPaused: boolean;
  CanSeek?: boolean; // Optional in progress, required in start (via extension)
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
// Server Info
// ============================================================================

export interface ServerInfo {
  Id: string;
  ServerName: string;
  Version: string;
  ProductName?: string;
  OperatingSystem?: string;
  HasUpdateAvailable?: boolean;
  CanSelfRestart?: boolean;
  CanLaunchWebUI?: boolean;
  StartupWizardCompleted?: boolean;
}

// ============================================================================
// Errors
// ============================================================================

export class MediaServerError extends Error {
  constructor(
    message: string,
    public statusCode?: number,
    public response?: unknown
  ) {
    super(message);
    this.name = 'MediaServerError';
  }
}

export class AuthenticationError extends MediaServerError {
  constructor(message: string, statusCode?: number, response?: unknown) {
    super(message, statusCode, response);
    this.name = 'AuthenticationError';
  }
}

export class NetworkError extends MediaServerError {
  constructor(
    message: string,
    public originalError?: Error
  ) {
    super(message);
    this.name = 'NetworkError';
  }
}
