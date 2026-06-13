import { type OfflineSource } from '@yay-tsa/platform';

export interface CacheEntryInfo {
  trackId: string;
  reasons: OfflineSource[];
  lastAccessedAt: number;
  size: number;
}

const PROTECTED_SOURCES: ReadonlySet<OfflineSource> = new Set([
  'manual',
  'favorite',
  'album',
  'playlist',
]);

// A track is a pure listening-cache entry only when it was auto-cached AND the
// user never expressed intent to keep it (manual / favorite / album / playlist).
// Those are the only entries the LRU is allowed to evict.
export function isEvictableCacheEntry(reasons: OfflineSource[]): boolean {
  return reasons.includes('listening-cache') && !reasons.some(r => PROTECTED_SOURCES.has(r));
}

// Drop the oldest listening-cache entries until their combined size fits within
// maxCacheBytes. Protected downloads are never returned and never count toward
// the budget. A non-positive budget means "unlimited" — nothing is evicted.
export function selectCacheEvictions(entries: CacheEntryInfo[], maxCacheBytes: number): string[] {
  if (maxCacheBytes <= 0) return [];
  const evictable = entries
    .filter(entry => isEvictableCacheEntry(entry.reasons))
    .sort((a, b) => a.lastAccessedAt - b.lastAccessedAt);

  let cacheBytes = evictable.reduce((total, entry) => total + entry.size, 0);
  const toEvict: string[] = [];
  for (const entry of evictable) {
    if (cacheBytes <= maxCacheBytes) break;
    toEvict.push(entry.trackId);
    cacheBytes -= entry.size;
  }
  return toEvict;
}
