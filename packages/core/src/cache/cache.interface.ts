/**
 * Cache interface
 * Abstract interface for caching layer implementations
 */

/**
 * Cache entry with TTL support
 */
export interface CacheEntry<T> {
  key: string;
  data: T;
  timestamp: number; // Unix timestamp in milliseconds
  ttl: number; // Time to live in milliseconds
  version: string; // Cache version for invalidation
}

/**
 * Cache configuration
 */
export interface CacheConfig {
  name: string; // Cache name/namespace
  version: string; // Cache version (for invalidation)
  maxSize?: number; // Maximum number of entries (optional)
}

/**
 * Abstract cache interface
 */
export interface ICache {
  /**
   * Get value from cache
   * Returns null if not found or expired
   */
  get<T>(key: string): Promise<T | null>;

  /**
   * Set value in cache with TTL
   */
  set<T>(key: string, value: T, ttl: number): Promise<void>;

  /**
   * Delete specific key from cache
   */
  delete(key: string): Promise<void>;

  /**
   * Clear all cache entries
   */
  clear(): Promise<void>;

  /**
   * Check if key exists and not expired
   */
  has(key: string): Promise<boolean>;

  /**
   * Get all keys in cache
   */
  keys(): Promise<string[]>;

  /**
   * Remove expired entries (cleanup)
   */
  cleanup(): Promise<void>;
}

/**
 * TTL constants (in milliseconds)
 */
export const TTL = {
  FIVE_MINUTES: 5 * 60 * 1000,
  THIRTY_MINUTES: 30 * 60 * 1000,
  ONE_HOUR: 60 * 60 * 1000,
  FOUR_HOURS: 4 * 60 * 60 * 1000,
  EIGHT_HOURS: 8 * 60 * 60 * 1000,
  ONE_DAY: 24 * 60 * 60 * 1000,
  ONE_WEEK: 7 * 24 * 60 * 60 * 1000,
} as const;
