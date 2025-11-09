/**
 * Cached Items Service
 * Wrapper around ItemsService with caching support
 */

import {
  ItemsService,
  type ItemsResult,
  type MusicAlbum,
  type MusicArtist,
  type AudioItem,
  TTL,
} from '@yaytsa/core';
import { cacheManager } from '../cache/cache-manager.js';

/**
 * Get albums with caching
 */
export async function getCachedAlbums(
  itemsService: ItemsService,
  options?: {
    parentId?: string;
    artistId?: string;
    genreId?: string;
    sortBy?: string;
    startIndex?: number;
    limit?: number;
    searchTerm?: string;
    isFavorite?: boolean;
  }
): Promise<ItemsResult<MusicAlbum>> {
  const cache = cacheManager.getApiCache();

  // Build cache key
  const cacheKey = cacheManager.buildCacheKey('/albums', options);

  // Try cache first
  if (cache) {
    const cached = await cache.get<ItemsResult<MusicAlbum>>(cacheKey);
    if (cached) {
      return cached;
    }
  }

  // Fetch from API
  const result = await itemsService.getAlbums(options);

  // Store in cache
  if (cache) {
    await cache.set(cacheKey, result, TTL.FOUR_HOURS);
  }

  return result;
}

/**
 * Get recent albums with caching (shorter TTL)
 */
export async function getCachedRecentAlbums(
  itemsService: ItemsService,
  limit?: number
): Promise<ItemsResult<MusicAlbum>> {
  const cache = cacheManager.getApiCache();

  const cacheKey = cacheManager.buildCacheKey('/recent-albums', { limit });

  if (cache) {
    const cached = await cache.get<ItemsResult<MusicAlbum>>(cacheKey);
    if (cached) {
      return cached;
    }
  }

  const result = await itemsService.getRecentAlbums(limit);

  if (cache) {
    // Shorter TTL for recent albums (more dynamic data)
    await cache.set(cacheKey, result, TTL.THIRTY_MINUTES);
  }

  return result;
}

/**
 * Get album tracks with caching
 */
export async function getCachedAlbumTracks(
  itemsService: ItemsService,
  albumId: string
): Promise<AudioItem[]> {
  const cache = cacheManager.getApiCache();

  const cacheKey = cacheManager.buildCacheKey(`/album-tracks/${albumId}`);

  if (cache) {
    const cached = await cache.get<AudioItem[]>(cacheKey);
    if (cached) {
      return cached;
    }
  }

  const result = await itemsService.getAlbumTracks(albumId);

  if (cache) {
    // Album tracks rarely change - long TTL
    await cache.set(cacheKey, result, TTL.ONE_DAY);
  }

  return result;
}

/**
 * Get single item with caching
 */
export async function getCachedItem(
  itemsService: ItemsService,
  itemId: string
): Promise<AudioItem | MusicAlbum | MusicArtist> {
  const cache = cacheManager.getApiCache();

  const cacheKey = cacheManager.buildCacheKey(`/item/${itemId}`);

  if (cache) {
    const cached: AudioItem | MusicAlbum | MusicArtist | null = await cache.get<
      AudioItem | MusicAlbum | MusicArtist
    >(cacheKey);
    if (cached) {
      return cached;
    }
  }

  const result = await itemsService.getItem(itemId);

  if (cache) {
    // Item metadata rarely changes
    await cache.set(cacheKey, result, TTL.EIGHT_HOURS);
  }

  return result;
}

/**
 * Invalidate album-related caches
 * Use after favoriting/unfavoriting albums
 */
export async function invalidateAlbumCaches(): Promise<void> {
  const cache = cacheManager.getApiCache();
  if (!cache) return;

  // Delete all album-related cache keys
  const keys = await cache.keys();
  const albumKeys = keys.filter(key => key.includes('/albums') || key.includes('/recent-albums'));

  for (const key of albumKeys) {
    await cache.delete(key);
  }
}
