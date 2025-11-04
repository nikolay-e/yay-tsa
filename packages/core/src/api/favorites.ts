/**
 * Favorites Module
 * Handles marking/unmarking items as favorites and querying favorite items
 */

import { JellyfinClient } from './client.js';
import { ItemsService } from './items.js';
import { UserItemData, MusicAlbum, AudioItem, ItemsResult } from '../models/types.js';

export class FavoritesService {
  private itemsService: ItemsService;

  constructor(private client: JellyfinClient) {
    this.itemsService = new ItemsService(client);
  }

  /**
   * Mark an item as favorite
   */
  async markFavorite(itemId: string): Promise<UserItemData> {
    const userId = this.client.requireAuth();

    const result = await this.client.post<UserItemData>(`/Users/${userId}/FavoriteItems/${itemId}`);
    if (!result) {
      throw new Error('Failed to mark item as favorite: Empty response');
    }
    return result;
  }

  /**
   * Unmark an item as favorite
   */
  async unmarkFavorite(itemId: string): Promise<UserItemData> {
    const userId = this.client.requireAuth();

    const result = await this.client.delete<UserItemData>(`/Users/${userId}/FavoriteItems/${itemId}`);
    if (!result) {
      throw new Error('Failed to unmark item as favorite: Empty response');
    }
    return result;
  }

  /**
   * Get all favorite albums
   */
  async getFavoriteAlbums(options?: {
    startIndex?: number;
    limit?: number;
  }): Promise<ItemsResult<MusicAlbum>> {
    return this.itemsService.getAlbums({
      ...options,
      isFavorite: true,
    });
  }

  /**
   * Get all favorite tracks
   */
  async getFavoriteTracks(options?: {
    startIndex?: number;
    limit?: number;
  }): Promise<ItemsResult<AudioItem>> {
    return this.itemsService.getTracks({
      ...options,
      isFavorite: true,
    });
  }

  /**
   * Get all favorite artists
   */
  async getFavoriteArtists(options?: {
    startIndex?: number;
    limit?: number;
  }): Promise<ItemsResult<any>> {
    return this.itemsService.getArtists({
      ...options,
      isFavorite: true,
    });
  }
}
