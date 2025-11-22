/**
 * Cache Manager
 * Centralized management of all application caches
 */

import { IndexedDBCache, APP_VERSION } from '@yaytsa/core';
import type { ICache } from '@yaytsa/core';

class CacheManager {
  private caches: Map<string, ICache> = new Map();
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

      // Ensure the cache is usable before marking initialized
      await apiCache.cleanup();
      this.caches.set('api', apiCache);

      this.initialized = true;
    } catch (error) {
      console.error('Failed to initialize cache manager:', error);
      // Continue without caching if initialization fails
      this.caches.clear();
      this.initialized = false;
    }
  }

  getApiCache(): ICache | null {
    if (!this.initialized) {
      return null;
    }
    return this.caches.get('api') || null;
  }

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

  async clearAll(): Promise<void> {
    if (!this.initialized) return;
    const promises: Promise<void>[] = [];
    for (const cache of this.caches.values()) {
      promises.push(cache.clear());
    }
    await Promise.all(promises);
  }

  async cleanup(): Promise<void> {
    if (!this.initialized) return;
    const promises: Promise<void>[] = [];
    for (const cache of this.caches.values()) {
      promises.push(cache.cleanup());
    }
    await Promise.all(promises);
  }

  close(): void {
    for (const cache of this.caches.values()) {
      const cacheWithClose = cache as unknown as { close?: () => void };
      if (cacheWithClose.close) {
        cacheWithClose.close();
      }
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
