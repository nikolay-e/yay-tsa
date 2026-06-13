import { describe, it, expect } from 'vitest';
import { type OfflineSource } from '@yay-tsa/platform';
import { selectCacheEvictions, isEvictableCacheEntry, type CacheEntryInfo } from './cache-eviction';

function entry(
  trackId: string,
  reasons: OfflineSource[],
  lastAccessedAt: number,
  size = 100
): CacheEntryInfo {
  return { trackId, reasons, lastAccessedAt, size };
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
  it('returns nothing when the cache fits within the byte budget', () => {
    const entries = [
      entry('a', ['listening-cache'], 1, 100),
      entry('b', ['listening-cache'], 2, 100),
    ];
    expect(selectCacheEvictions(entries, 1000)).toEqual([]);
  });

  it('evicts the oldest cache entries first until under the byte budget', () => {
    const entries = [
      entry('new', ['listening-cache'], 300, 100),
      entry('old', ['listening-cache'], 100, 100),
      entry('mid', ['listening-cache'], 200, 100),
    ];
    expect(selectCacheEvictions(entries, 100)).toEqual(['old', 'mid']);
  });

  it('never evicts manual or favorite downloads to satisfy the budget', () => {
    const entries = [
      entry('fav', ['favorite'], 1, 100),
      entry('manual', ['manual'], 2, 100),
      entry('cache1', ['listening-cache'], 3, 100),
      entry('cache2', ['listening-cache'], 4, 100),
    ];
    // Only the two cache entries count toward the budget; the oldest is evicted.
    expect(selectCacheEvictions(entries, 100)).toEqual(['cache1']);
  });

  it('treats a cache + favorite entry as protected and not counted', () => {
    const entries = [
      entry('protected', ['listening-cache', 'favorite'], 1, 1000),
      entry('cache', ['listening-cache'], 2, 100),
    ];
    expect(selectCacheEvictions(entries, 200)).toEqual([]);
  });

  it('treats a non-positive budget as unlimited', () => {
    const entries = [
      entry('a', ['listening-cache'], 1, 100),
      entry('b', ['listening-cache'], 2, 100),
    ];
    expect(selectCacheEvictions(entries, 0)).toEqual([]);
  });
});
