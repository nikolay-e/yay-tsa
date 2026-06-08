import { create } from 'zustand';
import {
  FavoritesService,
  ItemsService,
  PlaybackReporter,
  ticksToSeconds,
  secondsToTicks,
  type AudioItem,
} from '@yay-tsa/core';
import {
  IndexedDbOfflineStore,
  requestPersistentStorage,
  isStoragePersisted,
  estimateStorage,
  type OfflineSource,
} from '@yay-tsa/platform';
import { useAuthStore } from '@/features/auth/stores/auth.store';
import { log } from '@/shared/utils/logger';
import { collapseOutbox } from './outbox';
import { selectCacheEvictions } from './cache-eviction';

export type OfflineStatus = 'idle' | 'downloading' | 'ready' | 'error';

export interface OfflineEntry {
  status: OfflineStatus;
  progress: number; // 0..1 while downloading
  size: number;
  name: string;
  // Why this track is held offline. Drives LRU eviction: only pure
  // 'listening-cache' entries are ever auto-removed.
  reasons: OfflineSource[];
  // Last time the track was played — the LRU key for the listening cache.
  lastAccessedAt: number;
}

// User-tunable offline behaviour. Auto-download of favorites and auto-caching of
// played tracks are on by default; only the listening cache is size-bounded.
export interface OfflineSettings {
  autoDownloadFavorites: boolean;
  autoCachePlayed: boolean;
  maxCacheTracks: number; // <= 0 means unlimited
  removeUnlikedDownloads: boolean;
}

interface OfflineState {
  initialized: boolean;
  isOnline: boolean;
  persisted: boolean;
  usageBytes: number;
  quotaBytes: number;
  entries: Record<string, OfflineEntry>;
  // Full library items for every downloaded track, so the offline library can
  // render and start playback with no network (loaded from the manifest).
  items: Record<string, AudioItem>;
  settings: OfflineSettings;
}

interface OfflineActions {
  init: () => Promise<void>;
  download: (track: AudioItem, reason?: OfflineSource) => Promise<void>;
  downloadMany: (tracks: AudioItem[], reason?: OfflineSource) => Promise<void>;
  remove: (trackId: string) => Promise<void>;
  clearAll: () => Promise<void>;
  clearListeningCache: () => Promise<void>;
  getPlaybackUrl: (trackId: string, fallbackUrl: string) => Promise<string>;
  getCoverUrl: (track: AudioItem, fallbackUrl: string) => Promise<string>;
  cachePlayed: (track: AudioItem) => Promise<void>;
  autoFavorite: (itemId: string) => Promise<void>;
  removeFavorite: (itemId: string) => Promise<void>;
  reconcileFavorites: () => Promise<void>;
  enforceCacheLimit: () => Promise<void>;
  setSetting: (patch: Partial<OfflineSettings>) => void;
  queueFavorite: (itemId: string, makeFavorite: boolean) => Promise<void>;
  queueProgress: (trackId: string, positionSeconds: number) => Promise<void>;
  saveResume: (trackId: string, positionSeconds: number) => Promise<void>;
  flushOutbox: () => Promise<void>;
  refreshUsage: () => Promise<void>;
}

type OfflineStore = OfflineState & OfflineActions;

// Single IndexedDB-backed store for the whole app. Module-scoped so it survives
// component remounts and is shared by the player (which resolves blob URLs).
const store = new IndexedDbOfflineStore<AudioItem>();

// Object URLs are created lazily on first playback and reused for the lifetime of
// the download so repeated plays don't leak. Revoked on remove / clearAll.
const objectUrls = new Map<string, string>();
// Cover-art object URLs, keyed by cover key (album id, falling back to track id).
const coverUrls = new Map<string, string>();

const SETTINGS_STORAGE_KEY = 'yaytsa_offline_settings';
const DEFAULT_SETTINGS: OfflineSettings = {
  autoDownloadFavorites: true,
  autoCachePlayed: true,
  maxCacheTracks: 100,
  removeUnlikedDownloads: false,
};
// Favorites are fetched a page at a time so the whole set is reconciled even for
// large libraries, without one unbounded request.
const FAVORITE_PAGE_SIZE = 200;

let listenersAttached = false;
let initPromise: Promise<void> | null = null;
let flushInFlight = false;
let reconcileInFlight = false;

function loadSettings(): OfflineSettings {
  try {
    const raw = localStorage.getItem(SETTINGS_STORAGE_KEY);
    if (raw) {
      const parsed = JSON.parse(raw) as Partial<OfflineSettings>;
      return { ...DEFAULT_SETTINGS, ...parsed };
    }
  } catch {
    // Ignore storage / parse errors and fall back to defaults.
  }
  return { ...DEFAULT_SETTINGS };
}

function saveSettings(settings: OfflineSettings): void {
  try {
    localStorage.setItem(SETTINGS_STORAGE_KEY, JSON.stringify(settings));
  } catch {
    // Ignore storage errors
  }
}

function coverKeyFor(track: AudioItem): string {
  return track.AlbumId ?? track.Id;
}

function uniqueReasons(reasons: OfflineSource[], add: OfflineSource): OfflineSource[] {
  return reasons.includes(add) ? reasons : [...reasons, add];
}

function revokeObjectUrl(trackId: string): void {
  const url = objectUrls.get(trackId);
  if (url) {
    URL.revokeObjectURL(url);
    objectUrls.delete(trackId);
  }
}

function revokeAllObjectUrls(): void {
  for (const url of objectUrls.values()) URL.revokeObjectURL(url);
  objectUrls.clear();
  for (const url of coverUrls.values()) URL.revokeObjectURL(url);
  coverUrls.clear();
}

async function fetchBlobWithProgress(
  url: string,
  onProgress: (received: number, total: number) => void
): Promise<Blob> {
  const response = await fetch(url, { credentials: 'include' });
  if (!response.ok) {
    throw new Error(`Download failed: HTTP ${response.status}`);
  }

  const total = Number(response.headers.get('content-length') ?? 0);

  // Stream the body so the UI can show real progress on large files. Fall back
  // to a plain blob() when the body isn't readable (older Safari, some proxies).
  if (response.body && typeof response.body.getReader === 'function') {
    const reader = response.body.getReader();
    const chunks: Uint8Array[] = [];
    let received = 0;
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      if (value) {
        chunks.push(value);
        received += value.length;
        onProgress(received, total);
      }
    }
    const type = response.headers.get('content-type') ?? 'audio/mpeg';
    return new Blob(chunks as BlobPart[], { type });
  }

  return response.blob();
}

export const useOfflineStore = create<OfflineStore>()((set, get) => {
  // --- Internal manifest helpers (read-modify-write a single record) ---

  function patchEntry(trackId: string, patch: Partial<OfflineEntry>): void {
    set(state => {
      const entry = state.entries[trackId];
      if (!entry) return state;
      return { entries: { ...state.entries, [trackId]: { ...entry, ...patch } } };
    });
  }

  async function mergeReason(trackId: string, reason: OfflineSource): Promise<void> {
    const record = await store.getTrack(trackId);
    if (!record) return;
    const reasons = uniqueReasons(record.reasons ?? ['manual'], reason);
    const lastAccessedAt =
      reason === 'listening-cache' ? Date.now() : (record.lastAccessedAt ?? record.downloadedAt);
    await store.putTrackRecord({ ...record, reasons, lastAccessedAt });
    patchEntry(trackId, { reasons, lastAccessedAt });
  }

  async function setReasons(trackId: string, reasons: OfflineSource[]): Promise<void> {
    const record = await store.getTrack(trackId);
    if (!record) return;
    await store.putTrackRecord({ ...record, reasons });
    patchEntry(trackId, { reasons });
  }

  async function touchAccess(trackId: string): Promise<void> {
    const record = await store.getTrack(trackId);
    if (!record) return;
    const lastAccessedAt = Date.now();
    await store.putTrackRecord({ ...record, lastAccessedAt });
    patchEntry(trackId, { lastAccessedAt });
  }

  return {
    initialized: false,
    isOnline: typeof navigator !== 'undefined' ? navigator.onLine : true,
    persisted: false,
    usageBytes: 0,
    quotaBytes: 0,
    entries: {},
    items: {},
    settings: loadSettings(),

    init: async () => {
      if (initPromise) return initPromise;

      initPromise = (async () => {
        if (!store.isSupported()) {
          set({ initialized: true });
          return;
        }

        try {
          const records = await store.listTracks();
          const entries: Record<string, OfflineEntry> = {};
          const items: Record<string, AudioItem> = {};
          for (const record of records) {
            // Integrity check on launch: a manifest entry without its blob (evicted
            // under storage pressure) is dropped so the UI never offers a dead track.
            const blob = await store.getBlob(record.trackId);
            if (!blob || blob.size === 0) {
              await store.deleteTrack(record.trackId);
              continue;
            }
            entries[record.trackId] = {
              status: 'ready',
              progress: 1,
              size: record.size,
              name: record.metadata?.Name ?? 'Unknown',
              reasons: record.reasons ?? ['manual'],
              lastAccessedAt: record.lastAccessedAt ?? record.downloadedAt,
            };
            if (record.metadata) items[record.trackId] = record.metadata;
          }

          const persisted = await isStoragePersisted();
          set({ entries, items, persisted });
          await get().refreshUsage();
        } catch (error) {
          log.player.warn('Offline store init failed', { error: String(error) });
        } finally {
          set({ initialized: true });
        }
      })();

      if (typeof window !== 'undefined' && !listenersAttached) {
        listenersAttached = true;
        window.addEventListener('online', () => {
          set({ isOnline: true });
          get()
            .flushOutbox()
            .catch(() => {});
          get()
            .reconcileFavorites()
            .catch(() => {});
        });
        window.addEventListener('offline', () => set({ isOnline: false }));
      }

      await initPromise;
      // Replay anything queued from a previous offline session and make sure the
      // favorites set is fully downloaded.
      if (get().isOnline) {
        get()
          .flushOutbox()
          .catch(() => {});
        get()
          .reconcileFavorites()
          .catch(() => {});
      }
    },

    download: async (track, reason = 'manual') => {
      if (!store.isSupported()) return;
      const existing = get().entries[track.Id];
      if (existing?.status === 'downloading') return;
      if (existing?.status === 'ready') {
        // Already downloaded — just record the new reason (e.g. a cached track
        // that got favorited) and refresh the cache limit if relevant.
        await mergeReason(track.Id, reason);
        if (reason === 'listening-cache') await get().enforceCacheLimit();
        return;
      }

      const client = useAuthStore.getState().client;
      if (!client) {
        log.player.warn('Cannot download offline: not authenticated');
        return;
      }

      const now = Date.now();
      set(state => ({
        entries: {
          ...state.entries,
          [track.Id]: {
            status: 'downloading',
            progress: 0,
            size: 0,
            name: track.Name,
            reasons: [reason],
            lastAccessedAt: now,
          },
        },
      }));

      try {
        await requestPersistentStorage();
        const url = client.getStreamUrl(track.Id);
        const blob = await fetchBlobWithProgress(url, (received, total) => {
          const progress = total > 0 ? received / total : 0;
          set(state => {
            const entry = state.entries[track.Id];
            if (entry?.status !== 'downloading') return state;
            return { entries: { ...state.entries, [track.Id]: { ...entry, progress } } };
          });
        });

        await store.putTrack(
          {
            trackId: track.Id,
            size: blob.size,
            contentType: blob.type || 'audio/mpeg',
            downloadedAt: now,
            metadata: track,
            reasons: [reason],
            lastAccessedAt: now,
          },
          blob
        );

        // Persist cover art too so the offline library shows artwork. Best-effort:
        // a failed cover never fails the track download. Skip if already stored.
        const coverKey = coverKeyFor(track);
        try {
          if (!(await store.getCover(coverKey))) {
            const imageUrl = client.getImageUrl(coverKey, 'Primary', {
              tag: track.AlbumPrimaryImageTag,
              maxWidth: 256,
              maxHeight: 256,
            });
            if (imageUrl) {
              const coverResponse = await fetch(imageUrl, { credentials: 'include' });
              if (coverResponse.ok) await store.putCover(coverKey, await coverResponse.blob());
            }
          }
        } catch (error) {
          log.player.warn('Offline cover download failed', { coverKey, error: String(error) });
        }

        set(state => ({
          entries: {
            ...state.entries,
            [track.Id]: {
              status: 'ready',
              progress: 1,
              size: blob.size,
              name: track.Name,
              reasons: [reason],
              lastAccessedAt: now,
            },
          },
          items: { ...state.items, [track.Id]: track },
        }));
        await get().refreshUsage();
        if (reason === 'listening-cache') await get().enforceCacheLimit();
      } catch (error) {
        log.player.warn('Offline download failed', { trackId: track.Id, error: String(error) });
        set(state => ({
          entries: {
            ...state.entries,
            [track.Id]: {
              status: 'error',
              progress: 0,
              size: 0,
              name: track.Name,
              reasons: [reason],
              lastAccessedAt: now,
            },
          },
        }));
      }
    },

    downloadMany: async (tracks, reason = 'manual') => {
      // Sequential to bound bandwidth and memory — large albums won't fan out into
      // dozens of concurrent stream reads.
      for (const track of tracks) {
        await get().download(track, reason);
      }
    },

    remove: async trackId => {
      revokeObjectUrl(trackId);
      try {
        await store.deleteTrack(trackId);
      } catch (error) {
        log.player.warn('Offline remove failed', { trackId, error: String(error) });
      }
      set(state => {
        const entries = { ...state.entries };
        const items = { ...state.items };
        delete entries[trackId];
        delete items[trackId];
        return { entries, items };
      });
      await get().refreshUsage();
    },

    clearAll: async () => {
      revokeAllObjectUrls();
      try {
        await store.clearTracks();
      } catch (error) {
        log.player.warn('Offline clearAll failed', { error: String(error) });
      }
      set({ entries: {}, items: {} });
      await get().refreshUsage();
    },

    clearListeningCache: async () => {
      const entries = get().entries;
      for (const [trackId, entry] of Object.entries(entries)) {
        if (entry.status !== 'ready' || !entry.reasons.includes('listening-cache')) continue;
        const remaining = entry.reasons.filter(r => r !== 'listening-cache');
        if (remaining.length === 0) {
          await get().remove(trackId);
        } else {
          await setReasons(trackId, remaining);
        }
      }
    },

    // eslint-disable-next-line @typescript-eslint/require-await
    getPlaybackUrl: async (_trackId, fallbackUrl) => {
      // Always hand the player the same-origin stream URL — never a blob: URL, which a strict
      // `media-src 'self'` CSP rejects ("Media load rejected by URL safety check"). When the
      // track is downloaded, audio-offline-sw.js intercepts this exact URL and serves the bytes
      // from IndexedDB (Range-aware), so playback works offline without a CSP blob: exception.
      return fallbackUrl;
    },

    getCoverUrl: async (track, fallbackUrl) => {
      if (!store.isSupported()) return fallbackUrl;
      const key = coverKeyFor(track);

      const cached = coverUrls.get(key);
      if (cached) return cached;

      try {
        const blob = await store.getCover(key);
        if (blob && blob.size > 0) {
          const objectUrl = URL.createObjectURL(blob);
          coverUrls.set(key, objectUrl);
          return objectUrl;
        }
      } catch (error) {
        log.player.warn('Failed to resolve offline cover', { key, error: String(error) });
      }
      return fallbackUrl;
    },

    cachePlayed: async track => {
      if (!store.isSupported()) return;
      if (!get().settings.autoCachePlayed) return;
      if (!get().isOnline) return;
      const existing = get().entries[track.Id];
      if (existing?.status === 'downloading') return;
      if (existing?.status === 'ready') {
        // Refresh its LRU position so actively played tracks survive eviction.
        await touchAccess(track.Id);
        return;
      }
      await get().download(track, 'listening-cache');
    },

    autoFavorite: async itemId => {
      if (!store.isSupported()) return;
      if (!get().settings.autoDownloadFavorites) return;
      if (!get().isOnline) return;
      const client = useAuthStore.getState().client;
      if (!client) return;
      try {
        const items = new ItemsService(client);
        const item = await items.getItem(itemId);
        const type = (item as { Type?: string }).Type;
        if (type === 'Audio') {
          await get().download(item as AudioItem, 'favorite');
        } else if (type === 'MusicAlbum') {
          const tracks = await items.getAlbumTracks(itemId);
          await get().downloadMany(tracks, 'favorite');
        }
        // Favorited artists have no direct track list to download; the user can
        // bulk-download from the artist's albums instead.
      } catch (error) {
        log.player.warn('Auto-download favorite failed', { itemId, error: String(error) });
      }
    },

    removeFavorite: async itemId => {
      if (!store.isSupported()) return;
      const entry = get().entries[itemId];
      if (entry?.status !== 'ready' || !entry.reasons.includes('favorite')) return;
      const remaining = entry.reasons.filter(r => r !== 'favorite');
      if (remaining.length === 0) {
        if (get().settings.removeUnlikedDownloads) {
          await get().remove(itemId);
        } else {
          // Keep the file but demote it to a deliberate (manual) download.
          await setReasons(itemId, ['manual']);
        }
      } else {
        await setReasons(itemId, remaining);
        if (remaining.includes('listening-cache')) await get().enforceCacheLimit();
      }
    },

    reconcileFavorites: async () => {
      if (!store.isSupported()) return;
      if (!get().settings.autoDownloadFavorites) return;
      if (!get().isOnline) return;
      if (reconcileInFlight) return;
      const client = useAuthStore.getState().client;
      if (!client) return;

      reconcileInFlight = true;
      try {
        const items = new ItemsService(client);
        const favorites: AudioItem[] = [];
        for (let startIndex = 0; ; startIndex += FAVORITE_PAGE_SIZE) {
          const result = await items.getTracks({
            isFavorite: true,
            startIndex,
            limit: FAVORITE_PAGE_SIZE,
          });
          const page = result.Items ?? [];
          favorites.push(...page);
          const total = result.TotalRecordCount ?? favorites.length;
          if (page.length === 0 || favorites.length >= total) break;
        }

        for (const track of favorites) {
          const entry = get().entries[track.Id];
          if (entry?.status === 'ready') {
            if (!entry.reasons.includes('favorite')) await mergeReason(track.Id, 'favorite');
          } else if (entry?.status !== 'downloading') {
            await get().download(track, 'favorite');
          }
        }
      } catch (error) {
        log.player.warn('Favorites reconcile failed', { error: String(error) });
      } finally {
        reconcileInFlight = false;
      }
    },

    enforceCacheLimit: async () => {
      const { entries, settings } = get();
      const infos = Object.entries(entries)
        .filter(([, entry]) => entry.status === 'ready')
        .map(([trackId, entry]) => ({
          trackId,
          reasons: entry.reasons,
          lastAccessedAt: entry.lastAccessedAt,
        }));
      const toEvict = selectCacheEvictions(infos, settings.maxCacheTracks);
      for (const trackId of toEvict) {
        await get().remove(trackId);
      }
    },

    setSetting: patch => {
      const next = { ...get().settings, ...patch };
      saveSettings(next);
      set({ settings: next });
      if (patch.autoDownloadFavorites === true) {
        get()
          .reconcileFavorites()
          .catch(() => {});
      }
      if (patch.maxCacheTracks !== undefined) {
        get()
          .enforceCacheLimit()
          .catch(() => {});
      }
    },

    queueFavorite: async (itemId, makeFavorite) => {
      if (!store.isSupported()) return;
      try {
        await store.enqueue({
          kind: 'favorite',
          createdAt: Date.now(),
          payload: { itemId, makeFavorite },
        });
      } catch (error) {
        log.player.warn('Failed to queue favorite', { itemId, error: String(error) });
      }
    },

    queueProgress: async (trackId, positionSeconds) => {
      if (!store.isSupported()) return;
      try {
        await store.enqueue({
          kind: 'progress',
          createdAt: Date.now(),
          payload: { trackId, positionTicks: secondsToTicks(positionSeconds) },
        });
      } catch (error) {
        log.player.warn('Failed to queue progress', { trackId, error: String(error) });
      }
    },

    saveResume: async (trackId, positionSeconds) => {
      if (!store.isSupported()) return;
      try {
        const record = await store.getTrack(trackId);
        if (!record) return;
        const metadata = record.metadata;
        const updated: AudioItem = {
          ...metadata,
          UserData: {
            PlaybackPositionTicks: secondsToTicks(positionSeconds),
            PlayCount: metadata.UserData?.PlayCount ?? 0,
            IsFavorite: metadata.UserData?.IsFavorite ?? false,
            Played: metadata.UserData?.Played ?? false,
          },
        };
        await store.putTrackRecord({ ...record, metadata: updated });
        set(state =>
          state.items[trackId] ? { items: { ...state.items, [trackId]: updated } } : {}
        );
      } catch (error) {
        log.player.warn('Failed to save offline resume', { trackId, error: String(error) });
      }
    },

    flushOutbox: async () => {
      if (flushInFlight || !store.isSupported()) return;
      const client = useAuthStore.getState().client;
      if (!client) return;

      flushInFlight = true;
      try {
        const all = await store.listOutbox();
        if (all.length === 0) return;

        const { keep, staleIds } = collapseOutbox(all);
        for (const id of staleIds) await store.deleteOutbox(id);

        const favorites = new FavoritesService(client);
        const reporter = new PlaybackReporter(client);

        for (const entry of keep) {
          try {
            if (entry.kind === 'favorite') {
              const itemId = String(entry.payload.itemId);
              if (entry.payload.makeFavorite) await favorites.markFavorite(itemId);
              else await favorites.unmarkFavorite(itemId);
            } else if (entry.kind === 'progress') {
              const trackId = String(entry.payload.trackId);
              const seconds = ticksToSeconds(Number(entry.payload.positionTicks));
              await reporter.reportStopped(trackId, seconds);
            }
            if (entry.id !== undefined) await store.deleteOutbox(entry.id);
          } catch (error) {
            // Stop on first failure (likely still offline / server down). Remaining
            // entries stay queued and will be retried on the next flush.
            log.player.warn('Outbox flush interrupted', { error: String(error) });
            break;
          }
        }
      } catch (error) {
        log.player.warn('Outbox flush failed', { error: String(error) });
      } finally {
        flushInFlight = false;
      }
    },

    refreshUsage: async () => {
      const estimate = await estimateStorage();
      if (estimate) {
        set({ usageBytes: estimate.usage, quotaBytes: estimate.quota });
      } else {
        const usage = await store.getUsage().catch(() => 0);
        set({ usageBytes: usage });
      }
    },
  };
});

// Reconcile the favorites set whenever a user signs in (the client appears after
// init has already run for a fresh login).
useAuthStore.subscribe(
  state => state.client,
  client => {
    if (!client) return;
    const offline = useOfflineStore.getState();
    if (!offline.initialized) return;
    offline.reconcileFavorites().catch(() => {});
  }
);

export const useIsOnline = () => useOfflineStore(state => state.isOnline);
export const useOfflineEntry = (trackId: string): OfflineEntry | undefined =>
  useOfflineStore(state => state.entries[trackId]);
export const useOfflineSettings = () => useOfflineStore(state => state.settings);
