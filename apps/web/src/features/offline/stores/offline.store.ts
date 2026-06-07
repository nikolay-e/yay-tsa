import { create } from 'zustand';
import {
  FavoritesService,
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
} from '@yay-tsa/platform';
import { useAuthStore } from '@/features/auth/stores/auth.store';
import { log } from '@/shared/utils/logger';
import { collapseOutbox } from './outbox';

export type OfflineStatus = 'idle' | 'downloading' | 'ready' | 'error';

export interface OfflineEntry {
  status: OfflineStatus;
  progress: number; // 0..1 while downloading
  size: number;
  name: string;
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
}

interface OfflineActions {
  init: () => Promise<void>;
  download: (track: AudioItem) => Promise<void>;
  downloadMany: (tracks: AudioItem[]) => Promise<void>;
  remove: (trackId: string) => Promise<void>;
  clearAll: () => Promise<void>;
  getPlaybackUrl: (trackId: string, fallbackUrl: string) => Promise<string>;
  getCoverUrl: (track: AudioItem, fallbackUrl: string) => Promise<string>;
  queueFavorite: (itemId: string, makeFavorite: boolean) => Promise<void>;
  queueProgress: (trackId: string, positionSeconds: number) => Promise<void>;
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

let listenersAttached = false;
let initPromise: Promise<void> | null = null;
let flushInFlight = false;

function coverKeyFor(track: AudioItem): string {
  return track.AlbumId ?? track.Id;
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

export const useOfflineStore = create<OfflineStore>()((set, get) => ({
  initialized: false,
  isOnline: typeof navigator !== 'undefined' ? navigator.onLine : true,
  persisted: false,
  usageBytes: 0,
  quotaBytes: 0,
  entries: {},
  items: {},

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
      });
      window.addEventListener('offline', () => set({ isOnline: false }));
    }

    await initPromise;
    // Replay anything queued from a previous offline session.
    if (get().isOnline) {
      get()
        .flushOutbox()
        .catch(() => {});
    }
  },

  download: async track => {
    if (!store.isSupported()) return;
    const existing = get().entries[track.Id];
    if (existing?.status === 'ready' || existing?.status === 'downloading') return;

    const client = useAuthStore.getState().client;
    if (!client) {
      log.player.warn('Cannot download offline: not authenticated');
      return;
    }

    set(state => ({
      entries: {
        ...state.entries,
        [track.Id]: { status: 'downloading', progress: 0, size: 0, name: track.Name },
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
          downloadedAt: Date.now(),
          metadata: track,
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
          [track.Id]: { status: 'ready', progress: 1, size: blob.size, name: track.Name },
        },
        items: { ...state.items, [track.Id]: track },
      }));
      await get().refreshUsage();
    } catch (error) {
      log.player.warn('Offline download failed', { trackId: track.Id, error: String(error) });
      set(state => ({
        entries: {
          ...state.entries,
          [track.Id]: { status: 'error', progress: 0, size: 0, name: track.Name },
        },
      }));
    }
  },

  downloadMany: async tracks => {
    // Sequential to bound bandwidth and memory — large albums won't fan out into
    // dozens of concurrent stream reads.
    for (const track of tracks) {
      await get().download(track);
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

  getPlaybackUrl: async (trackId, fallbackUrl) => {
    if (!store.isSupported()) return fallbackUrl;

    const cached = objectUrls.get(trackId);
    if (cached) return cached;

    try {
      const blob = await store.getBlob(trackId);
      if (blob && blob.size > 0) {
        const objectUrl = URL.createObjectURL(blob);
        objectUrls.set(trackId, objectUrl);
        return objectUrl;
      }
    } catch (error) {
      log.player.warn('Failed to resolve offline blob', { trackId, error: String(error) });
    }
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
}));

export const useIsOnline = () => useOfflineStore(state => state.isOnline);
export const useOfflineEntry = (trackId: string): OfflineEntry | undefined =>
  useOfflineStore(state => state.entries[trackId]);
