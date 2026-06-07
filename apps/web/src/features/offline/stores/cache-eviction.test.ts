import { describe, it, expect } from 'vitest';
import { type OfflineSource } from '@yay-tsa/platform';
import { selectCacheEvictions, isEvictableCacheEntry, type CacheEntryInfo } from './cache-eviction';

function entry(trackId: string, reasons: OfflineSource[], lastAccessedAt: number): CacheEntryInfo {
  return { trackId, reasons, lastAccessedAt };
}

describe('isEvictableCacheEntry', () => {
  it('evicts a pure listening-cache entry', () => {
    expect(isEvictableCacheEntry(['listening-cache'])).toBe(true);
  });

  it('protects a cache entry that is also a favorite', () => {
    expect(isEvictableCacheEntry(['listening-cache', 'favorite'])).toBe(false);
  });

  it('protects manual / album / playlist downloads', () => {
    expect(isEvictableCacheEntry(['manual'])).toBe(false);
    expect(isEvictableCacheEntry(['album'])).toBe(false);
    expect(isEvictableCacheEntry(['playlist'])).toBe(false);
  });
});

describe('selectCacheEvictions', () => {
  it('returns nothing when the cache is under the limit', () => {
    const entries = [entry('a', ['listening-cache'], 1), entry('b', ['listening-cache'], 2)];
    expect(selectCacheEvictions(entries, 5)).toEqual([]);
  });

  it('evicts the oldest cache entries first beyond the limit', () => {
    const entries = [
      entry('new', ['listening-cache'], 300),
      entry('old', ['listening-cache'], 100),
      entry('mid', ['listening-cache'], 200),
    ];
    expect(selectCacheEvictions(entries, 1)).toEqual(['old', 'mid']);
  });

  it('never evicts manual or favorite downloads to satisfy the limit', () => {
    const entries = [
      entry('fav', ['favorite'], 1),
      entry('manual', ['manual'], 2),
      entry('cache1', ['listening-cache'], 3),
      entry('cache2', ['listening-cache'], 4),
    ];
    // Only the two cache entries count toward the limit; the oldest is evicted.
    expect(selectCacheEvictions(entries, 1)).toEqual(['cache1']);
  });

  it('treats a cache + favorite entry as protected and not counted', () => {
    const entries = [
      entry('protected', ['listening-cache', 'favorite'], 1),
      entry('cache', ['listening-cache'], 2),
    ];
    expect(selectCacheEvictions(entries, 1)).toEqual([]);
  });

  it('treats a non-positive limit as unlimited', () => {
    const entries = [entry('a', ['listening-cache'], 1), entry('b', ['listening-cache'], 2)];
    expect(selectCacheEvictions(entries, 0)).toEqual([]);
  });
});
