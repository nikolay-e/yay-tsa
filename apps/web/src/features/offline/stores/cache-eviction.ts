import { type OfflineSource } from '@yay-tsa/platform';

export interface CacheEntryInfo {
  trackId: string;
  reasons: OfflineSource[];
  lastAccessedAt: number;
}

const PROTECTED_SOURCES: readonly OfflineSource[] = ['manual', 'favorite', 'album', 'playlist'];

// A track is a pure listening-cache entry only when it was auto-cached AND the
// user never expressed intent to keep it (manual / favorite / album / playlist).
// Those are the only entries the LRU is allowed to evict.
export function isEvictableCacheEntry(reasons: OfflineSource[]): boolean {
  return reasons.includes('listening-cache') && !reasons.some(r => PROTECTED_SOURCES.includes(r));
}

// Pick the oldest listening-cache entries to drop so the cache stays within
// maxCacheTracks. Protected downloads are never returned and never count toward
// the limit. A non-positive limit means "unlimited" — nothing is evicted.
export function selectCacheEvictions(entries: CacheEntryInfo[], maxCacheTracks: number): string[] {
  if (maxCacheTracks <= 0) return [];
  const evictable = entries
    .filter(entry => isEvictableCacheEntry(entry.reasons))
    .sort((a, b) => a.lastAccessedAt - b.lastAccessedAt);
  const overflow = evictable.length - maxCacheTracks;
  if (overflow <= 0) return [];
  return evictable.slice(0, overflow).map(entry => entry.trackId);
}
