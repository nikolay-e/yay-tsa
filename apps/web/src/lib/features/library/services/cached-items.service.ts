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
import { cacheManager } from '../../../cache/cache-manager.js';

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
 * Get recently played albums with caching
 * Falls back to random albums if no play history
 */
export async function getCachedRecentlyPlayedAlbums(
  itemsService: ItemsService,
  limit?: number
): Promise<{ items: MusicAlbum[]; isRandom: boolean }> {
  const cache = cacheManager.getApiCache();

  const cacheKey = cacheManager.buildCacheKey('/recently-played-albums', { limit });

  if (cache) {
    const cached = await cache.get<{ items: MusicAlbum[]; isRandom: boolean }>(cacheKey);
    if (cached) {
      return cached;
    }
  }

  // Try to get recently played albums
  const recentlyPlayed = await itemsService.getRecentlyPlayedAlbums(limit);

  let result: { items: MusicAlbum[]; isRandom: boolean };

  if (recentlyPlayed.TotalRecordCount > 0) {
    result = { items: recentlyPlayed.Items, isRandom: false };
  } else {
    // Fallback to random albums if no play history
    const randomAlbums = await itemsService.getRandomAlbums(limit);
    result = { items: randomAlbums.Items, isRandom: true };
  }

  if (cache) {
    // Short TTL - play history changes frequently
    await cache.set(cacheKey, result, TTL.FIVE_MINUTES);
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
 * Get artists with caching
 */
export async function getCachedArtists(
  itemsService: ItemsService,
  options?: {
    sortBy?: string;
    startIndex?: number;
    limit?: number;
    searchTerm?: string;
    isFavorite?: boolean;
  }
): Promise<ItemsResult<MusicArtist>> {
  const cache = cacheManager.getApiCache();

  const cacheKey = cacheManager.buildCacheKey('/artists', options);

  if (cache) {
    const cached = await cache.get<ItemsResult<MusicArtist>>(cacheKey);
    if (cached) {
      return cached;
    }
  }

  const result = await itemsService.getArtists(options);

  if (cache) {
    await cache.set(cacheKey, result, TTL.FOUR_HOURS);
  }

  return result;
}

/**
 * Get albums from an artist with caching
 */
export async function getCachedArtistAlbums(
  itemsService: ItemsService,
  artistId: string
): Promise<MusicAlbum[]> {
  const cache = cacheManager.getApiCache();

  const cacheKey = cacheManager.buildCacheKey(`/artist-albums/${artistId}`);

  if (cache) {
    const cached = await cache.get<MusicAlbum[]>(cacheKey);
    if (cached) {
      return cached;
    }
  }

  const result = await itemsService.getArtistAlbums(artistId);

  if (cache) {
    await cache.set(cacheKey, result, TTL.FOUR_HOURS);
  }

  return result;
}
