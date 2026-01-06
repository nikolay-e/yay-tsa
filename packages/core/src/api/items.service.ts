/**
 * Library Items Module
 * Handles queries for music library items (albums, artists, tracks)
 */

import {
  type ItemsQuery,
  type ItemsResult,
  type AudioItem,
  type MusicAlbum,
  type MusicArtist,
  MediaServerError,
} from '../internal/models/types.js';
import { BaseService } from './base-api.service.js';

export class ItemsService extends BaseService {
  /**
   * Query items from library
   * Generic method for all item types
   */
  async queryItems<T = unknown>(query: ItemsQuery): Promise<ItemsResult<T>> {
    const userId = this.requireAuth();

    // Build query parameters (userId is required for /Items endpoint)
    const params: Record<string, string | number | boolean | string[]> = {
      userId,
    };

    if (query.ParentId) params.ParentId = query.ParentId;
    if (query.IncludeItemTypes) params.IncludeItemTypes = query.IncludeItemTypes;
    if (query.Recursive !== undefined) params.Recursive = query.Recursive;
    if (query.SortBy) params.SortBy = query.SortBy;
    if (query.SortOrder) params.SortOrder = query.SortOrder;
    if (query.StartIndex !== undefined) params.StartIndex = query.StartIndex;
    if (query.Limit !== undefined) params.Limit = query.Limit;
    if (query.SearchTerm) params.SearchTerm = query.SearchTerm;
    if (query.ArtistIds) params.ArtistIds = query.ArtistIds.join(',');
    if (query.AlbumIds) params.AlbumIds = query.AlbumIds.join(',');
    if (query.GenreIds) params.GenreIds = query.GenreIds.join(',');
    if (query.IsFavorite !== undefined) params.IsFavorite = query.IsFavorite;

    // Fields to include in response
    if (query.Fields && query.Fields.length > 0) {
      params.Fields = query.Fields.join(',');
    }

    const result = await this.client.get<ItemsResult<T>>('/Items', params);
    if (!result) {
      throw new MediaServerError('Failed to query items: Empty response');
    }
    return result;
  }

  /**
   * Get music albums
   */
  async getAlbums(options?: {
    parentId?: string;
    artistId?: string;
    genreId?: string;
    sortBy?: string;
    startIndex?: number;
    limit?: number;
    searchTerm?: string;
    isFavorite?: boolean;
  }): Promise<ItemsResult<MusicAlbum>> {
    const query: ItemsQuery = {
      IncludeItemTypes: 'MusicAlbum',
      Recursive: true,
      Fields: ['PrimaryImageAspectRatio', 'Genres', 'DateCreated', 'Artists', 'ImageTags'],
      SortBy: options?.sortBy ?? 'SortName',
      SortOrder: 'Ascending',
      StartIndex: options?.startIndex,
      Limit: options?.limit,
      SearchTerm: options?.searchTerm,
      IsFavorite: options?.isFavorite,
    };

    if (options?.parentId) {
      query.ParentId = options.parentId;
    }

    if (options?.artistId) {
      query.ArtistIds = [options.artistId];
    }

    if (options?.genreId) {
      query.GenreIds = [options.genreId];
    }

    return this.queryItems<MusicAlbum>(query);
  }

  /**
   * Get music artists
   */
  async getArtists(options?: {
    startIndex?: number;
    limit?: number;
    searchTerm?: string;
    isFavorite?: boolean;
    sortBy?: string;
  }): Promise<ItemsResult<MusicArtist>> {
    const query: ItemsQuery = {
      IncludeItemTypes: 'MusicArtist',
      Recursive: true,
      Fields: ['PrimaryImageAspectRatio', 'Genres', 'DateCreated', 'Overview', 'ChildCount', 'ImageTags'],
      SortBy: options?.sortBy ?? 'SortName',
      SortOrder: 'Ascending',
      StartIndex: options?.startIndex,
      Limit: options?.limit,
      SearchTerm: options?.searchTerm,
      IsFavorite: options?.isFavorite,
    };

    return this.queryItems<MusicArtist>(query);
  }

  /**
   * Get audio tracks
   */
  async getTracks(options?: {
    parentId?: string;
    albumId?: string;
    artistId?: string;
    sortBy?: string;
    startIndex?: number;
    limit?: number;
    searchTerm?: string;
    isFavorite?: boolean;
  }): Promise<ItemsResult<AudioItem>> {
    const query: ItemsQuery = {
      IncludeItemTypes: 'Audio',
      Recursive: true,
      Fields: [
        'MediaSources',
        'Artists',
        'Album',
        'AlbumId',
        'AlbumPrimaryImageTag',
        'Genres',
        'DateCreated',
        'RunTimeTicks', // Required for track duration display and playback state
        'PrimaryImageAspectRatio',
      ],
      SortBy: options?.sortBy ?? 'ParentIndexNumber,IndexNumber,SortName',
      SortOrder: 'Ascending',
      StartIndex: options?.startIndex,
      Limit: options?.limit,
      SearchTerm: options?.searchTerm,
      IsFavorite: options?.isFavorite,
    };

    if (options?.parentId) {
      query.ParentId = options.parentId;
    }

    if (options?.albumId) {
      query.AlbumIds = [options.albumId];
    }

    if (options?.artistId) {
      query.ArtistIds = [options.artistId];
    }

    return this.queryItems<AudioItem>(query);
  }

  /**
   * Get single item by ID
   */
  async getItem(itemId: string): Promise<AudioItem | MusicAlbum | MusicArtist> {
    const result = await this.client.get<AudioItem | MusicAlbum | MusicArtist>(
      this.buildUserUrl(`/Items/${itemId}`)
    );
    if (!result) {
      throw new MediaServerError(`Failed to get item ${itemId}: Empty response`);
    }
    return result;
  }

  /**
   * Get tracks from an album
   * Uses dedicated /Items/{albumId}/Tracks endpoint for proper disc/track sorting
   */
  async getAlbumTracks(albumId: string): Promise<AudioItem[]> {
    const userId = this.requireAuth();
    const result = await this.client.get<ItemsResult<AudioItem>>(`/Items/${albumId}/Tracks`, {
      userId,
      Fields: 'MediaSources,Artists,Album,AlbumId,AlbumPrimaryImageTag,RunTimeTicks',
    });
    return result?.Items ?? [];
  }

  /**
   * Get albums from an artist
   */
  async getArtistAlbums(artistId: string): Promise<MusicAlbum[]> {
    const result = await this.getAlbums({ artistId });
    return result.Items;
  }

  /**
   * Get tracks from an artist
   */
  async getArtistTracks(artistId: string): Promise<AudioItem[]> {
    const result = await this.getTracks({ artistId });
    return result.Items;
  }

  /**
   * Search across all music items
   */
  async search(
    searchTerm: string,
    options?: {
      limit?: number;
    }
  ): Promise<{
    albums: MusicAlbum[];
    artists: MusicArtist[];
    tracks: AudioItem[];
  }> {
    const limit = options?.limit ?? 20;

    const [albums, artists, tracks] = await Promise.all([
      this.getAlbums({ searchTerm, limit }),
      this.getArtists({ searchTerm, limit }),
      this.getTracks({ searchTerm, limit }),
    ]);

    return {
      albums: albums.Items,
      artists: artists.Items,
      tracks: tracks.Items,
    };
  }

  /**
   * Get recently added albums
   */
  async getRecentAlbums(limit?: number): Promise<ItemsResult<MusicAlbum>> {
    return this.getAlbums({
      sortBy: 'DateCreated',
      limit,
    });
  }

  /**
   * Get recently played albums (sorted by last play date)
   */
  async getRecentlyPlayedAlbums(limit?: number): Promise<ItemsResult<MusicAlbum>> {
    const userId = this.requireAuth();

    const params: Record<string, string | number | boolean> = {
      userId,
      IncludeItemTypes: 'MusicAlbum',
      Recursive: true,
      SortBy: 'DatePlayed',
      SortOrder: 'Descending',
      Filters: 'IsPlayed',
      enableUserData: true,
      Fields: 'PrimaryImageAspectRatio,Genres,DateCreated,Artists,ImageTags',
    };

    if (limit !== undefined) {
      params.Limit = limit;
    }

    const result = await this.client.get<ItemsResult<MusicAlbum>>('/Items', params);
    if (!result) {
      throw new MediaServerError('Failed to get recently played albums: Empty response');
    }
    return result;
  }

  /**
   * Get random albums
   */
  async getRandomAlbums(limit?: number): Promise<ItemsResult<MusicAlbum>> {
    return this.getAlbums({
      sortBy: 'Random',
      limit,
    });
  }

  /**
   * Get stream URL for audio item
   */
  getStreamUrl(itemId: string): string {
    return this.client.getStreamUrl(itemId);
  }

  /**
   * Get image URL for item
   */
  getImageUrl(
    itemId: string,
    imageType: string = 'Primary',
    options?: {
      tag?: string;
      maxWidth?: number;
      maxHeight?: number;
    }
  ): string {
    return this.client.getImageUrl(itemId, imageType, options);
  }
}
