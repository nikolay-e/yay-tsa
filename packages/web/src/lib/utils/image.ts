/**
 * Image URL utilities with caching support
 */

import { get } from 'svelte/store';
import { client } from '../stores/auth.js';
import { cacheManager } from '../cache/cache-manager.js';

/**
 * Image size presets
 */
type ImageSize = 'small' | 'medium' | 'large';

const IMAGE_SIZE_DIMENSIONS: Record<ImageSize, { maxWidth: number; maxHeight: number }> = {
  small: { maxWidth: 150, maxHeight: 150 },
  medium: { maxWidth: 300, maxHeight: 300 },
  large: { maxWidth: 600, maxHeight: 600 },
};

/**
 * Get image URL for an item
 */
export function getImageUrl(
  itemId: string,
  imageType: string = 'Primary',
  options?: {
    tag?: string;
    maxWidth?: number;
    maxHeight?: number;
    quality?: number;
  }
): string {
  const $client = get(client);

  if (!$client) {
    return '';
  }

  return $client.getImageUrl(itemId, imageType, options);
}

/**
 * Get album art URL with preset size
 */
export function getAlbumArtUrl(itemId: string, size: ImageSize = 'medium'): string {
  const dimensions = IMAGE_SIZE_DIMENSIONS[size];
  return getImageUrl(itemId, 'Primary', dimensions);
}

/**
 * Get cached album art URL (with blob caching)
 * Returns object URL from IndexedDB cache or fetches and caches
 */
export async function getCachedAlbumArtUrl(
  itemId: string,
  size: ImageSize = 'medium'
): Promise<string> {
  const imageCache = cacheManager.getImageCache();

  // If no cache available, fallback to direct URL
  if (!imageCache) {
    return getAlbumArtUrl(itemId, size);
  }

  // Try to get from cache first
  const cachedUrl = await imageCache.getObjectUrl(itemId, 'Primary', size);
  if (cachedUrl) {
    return cachedUrl;
  }

  // Not in cache - fetch, cache, and return
  const imageUrl = getAlbumArtUrl(itemId, size);
  if (!imageUrl) {
    return '';
  }

  try {
    const objectUrl = await imageCache.fetchAndCache(itemId, 'Primary', size, imageUrl);
    return objectUrl;
  } catch (error) {
    console.error('Failed to cache image:', error);
    // Fallback to direct URL on error
    return imageUrl;
  }
}

/**
 * Preload album art into cache (background operation)
 * Does not return URL - just ensures it's cached for later use
 */
export async function preloadAlbumArt(itemId: string, size: ImageSize = 'medium'): Promise<void> {
  const imageCache = cacheManager.getImageCache();

  if (!imageCache) {
    return;
  }

  // Check if already cached
  const cached = await imageCache.get(itemId, 'Primary', size);
  if (cached) {
    return; // Already cached
  }

  // Fetch and cache in background
  const imageUrl = getAlbumArtUrl(itemId, size);
  if (imageUrl) {
    try {
      await imageCache.fetchAndCache(itemId, 'Primary', size, imageUrl);
    } catch {
      // Silently fail for background preloading (non-critical)
      // Skip logging in production
    }
  }
}

/**
 * Preload multiple album arts (batch operation)
 */
export async function preloadAlbumArts(
  itemIds: string[],
  size: ImageSize = 'medium'
): Promise<void> {
  // Preload in parallel (but limit concurrency to avoid overwhelming network)
  const BATCH_SIZE = 5;

  for (let i = 0; i < itemIds.length; i += BATCH_SIZE) {
    const batch = itemIds.slice(i, i + BATCH_SIZE);
    await Promise.all(batch.map(async id => preloadAlbumArt(id, size)));
  }
}
