/**
 * Playlists Module
 * Handles creating and managing playlists
 */

import { BaseService } from './base-service.js';
import {
  Playlist,
  AudioItem,
  ItemsResult,
  CreatePlaylistDto,
  JellyfinError,
} from '../models/types.js';

export class PlaylistsService extends BaseService {
  /**
   * Create a new playlist
   */
  async createPlaylist(options: CreatePlaylistDto): Promise<Playlist> {
    const userId = this.requireAuth();

    // Build request body matching Jellyfin API schema
    const body: {
      Name: string;
      Ids?: string[];
      MediaType?: string;
      UserId: string;
      IsPublic?: boolean;
    } = {
      Name: options.name,
      UserId: options.userId || userId,
    };

    if (options.itemIds && options.itemIds.length > 0) {
      body.Ids = options.itemIds;
    }

    if (options.mediaType) {
      body.MediaType = options.mediaType;
    }

    if (options.isPublic !== undefined) {
      body.IsPublic = options.isPublic;
    }

    const result = await this.client.post<{ Id: string }>('/Playlists', body);
    if (!result) {
      throw new JellyfinError('Failed to create playlist: Empty response');
    }

    return this.getPlaylist(result.Id);
  }

  /**
   * Get playlist details by ID
   */
  async getPlaylist(playlistId: string): Promise<Playlist> {
    const userId = this.requireAuth();

    const result = await this.client.get<Playlist>(`/Users/${userId}/Items/${playlistId}`);
    if (!result) {
      throw new JellyfinError(`Failed to get playlist ${playlistId}: Empty response`);
    }
    return result;
  }

  /**
   * Get items in a playlist
   */
  async getPlaylistItems(
    playlistId: string,
    options?: {
      startIndex?: number;
      limit?: number;
    }
  ): Promise<ItemsResult<AudioItem>> {
    const userId = this.requireAuth();

    const params: Record<string, any> = {
      UserId: userId,
    };

    if (options?.startIndex !== undefined) {
      params.StartIndex = options.startIndex;
    }

    if (options?.limit !== undefined) {
      params.Limit = options.limit;
    }

    const result = await this.client.get<ItemsResult<AudioItem>>(
      `/Playlists/${playlistId}/Items`,
      params
    );
    if (!result) {
      throw new JellyfinError(`Failed to get playlist items for ${playlistId}: Empty response`);
    }
    return result;
  }

  /**
   * Add items to a playlist
   */
  async addItemsToPlaylist(playlistId: string, itemIds: string[]): Promise<void> {
    const userId = this.requireAuth();

    const idsParam = itemIds.join(',');
    const url = `/Playlists/${playlistId}/Items?Ids=${encodeURIComponent(idsParam)}&UserId=${userId}`;

    await this.client.post(url, undefined);
  }

  /**
   * Remove items from a playlist
   * @param entryIds The playlist entry IDs (not the original item IDs!)
   */
  async removeItemsFromPlaylist(playlistId: string, entryIds: string[]): Promise<void> {
    this.requireAuth();

    const entryIdsParam = entryIds.join(',');
    const url = `/Playlists/${playlistId}/Items?EntryIds=${encodeURIComponent(entryIdsParam)}`;

    await this.client.delete(url);
  }

  /**
   * Move/reorder an item in a playlist
   * @param itemId The playlist entry ID (PlaylistItemId from getPlaylistItems)
   * @param newIndex The new position index
   */
  async movePlaylistItem(playlistId: string, itemId: string, newIndex: number): Promise<void> {
    await this.client.post(`/Playlists/${playlistId}/Items/${itemId}/Move/${newIndex}`);
  }

  /**
   * Delete a playlist
   */
  async deletePlaylist(playlistId: string): Promise<void> {
    await this.client.delete(`/Items/${playlistId}`);
  }

  /**
   * Get all playlists for the user
   */
  async getPlaylists(options?: {
    startIndex?: number;
    limit?: number;
  }): Promise<ItemsResult<Playlist>> {
    const userId = this.requireAuth();

    const params: Record<string, any> = {
      IncludeItemTypes: 'Playlist',
      Recursive: true,
      Fields: 'PrimaryImageAspectRatio,ChildCount',
      SortBy: 'SortName',
      SortOrder: 'Ascending',
    };

    if (options?.startIndex !== undefined) {
      params.StartIndex = options.startIndex;
    }

    if (options?.limit !== undefined) {
      params.Limit = options.limit;
    }

    const result = await this.client.get<ItemsResult<Playlist>>(`/Users/${userId}/Items`, params);
    if (!result) {
      throw new JellyfinError('Failed to get playlists: Empty response');
    }
    return result;
  }
}
