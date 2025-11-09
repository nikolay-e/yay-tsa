/**
 * Cache Manager
 * Centralized management of all application caches
 */

import { IndexedDBCache, APP_VERSION } from '@yaytsa/core';
import type { ICache } from '@yaytsa/core';
import { ImageCache } from './image-cache.js';

/**
 * Cache manager singleton
 * Manages multiple cache instances for different data types
 */
class CacheManager {
  private caches: Map<string, ICache> = new Map();
  private imageCache: ImageCache | null = null;
  private initialized = false;

  /**
   * Initialize cache manager
   */
  async init(): Promise<void> {
    if (this.initialized) {
      return;
    }

    try {
      // Create cache for API responses
      const apiCache = new IndexedDBCache({
        name: 'api-responses',
        version: APP_VERSION,
      });

      this.caches.set('api', apiCache);

      // Create image blob cache
      this.imageCache = new ImageCache();

      // Run cleanup on initialization
      await this.cleanup();

      this.initialized = true;
    } catch (error) {
      console.error('Failed to initialize cache manager:', error);
      // Continue without caching if initialization fails
    }
  }

  /**
   * Get API cache instance
   */
  getApiCache(): ICache | null {
    return this.caches.get('api') || null;
  }

  /**
   * Get image cache instance
   */
  getImageCache(): ImageCache | null {
    return this.imageCache;
  }

  /**
   * Build cache key from URL and params
   */
  buildCacheKey(url: string, params?: Record<string, unknown>): string {
    if (!params || Object.keys(params).length === 0) {
      return url;
    }

    // Sort params for consistent keys
    const sortedParams = Object.keys(params)
      .sort()
      .map(key => `${key}=${JSON.stringify(params[key])}`)
      .join('&');

    return `${url}?${sortedParams}`;
  }

  /**
   * Clear all caches (API + Images)
   */
  async clearAll(): Promise<void> {
    const promises: Promise<void>[] = [];

    // Clear API caches
    for (const cache of this.caches.values()) {
      promises.push(cache.clear());
    }

    // Clear image cache
    if (this.imageCache) {
      promises.push(this.imageCache.clear());
    }

    await Promise.all(promises);
  }

  /**
   * Run cleanup on all caches (remove expired/old entries)
   */
  async cleanup(): Promise<void> {
    const promises: Promise<void>[] = [];

    // Cleanup API caches
    for (const cache of this.caches.values()) {
      promises.push(cache.cleanup());
    }

    // Cleanup old images (>7 days)
    if (this.imageCache) {
      promises.push(this.imageCache.cleanup());
    }

    await Promise.all(promises);
  }

  /**
   * Close all cache connections
   */
  close(): void {
    // Close API caches (ICache interface doesn't have close method, but implementation might)
    for (const cache of this.caches.values()) {
      // IndexedDBCache has a close() method
      const cacheWithClose = cache as unknown as { close?: () => void };
      if (cacheWithClose.close) {
        cacheWithClose.close();
      }
    }

    // Close image cache
    if (this.imageCache) {
      this.imageCache.close();
      this.imageCache = null;
    }

    this.caches.clear();
    this.initialized = false;
  }
}

// Export singleton instance
export const cacheManager = new CacheManager();

/**
 * Helper to get cached data or fetch and cache it
 */
export async function getCachedOrFetch<T>(
  cacheKey: string,
  fetchFn: () => Promise<T>,
  ttl: number
): Promise<T> {
  const cache = cacheManager.getApiCache();

  if (!cache) {
    // No cache available, just fetch
    return fetchFn();
  }

  // Try to get from cache
  const cached = await cache.get<T>(cacheKey);
  if (cached !== null) {
    return cached;
  }

  // Not in cache, fetch and store
  const data = await fetchFn();
  await cache.set(cacheKey, data, ttl);

  return data;
}
