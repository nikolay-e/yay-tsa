import { createLogger } from '@yay-tsa/core';

const log = createLogger('OfflineStore');

const DB_NAME = 'yaytsa-offline';
const DB_VERSION = 2;
const STORE_TRACKS = 'tracks';
const STORE_BLOBS = 'blobs';
const STORE_OUTBOX = 'outbox';
const STORE_COVERS = 'covers';

// Manifest entry for a single downloaded track. `metadata` carries the full
// library item (typed by the web layer as AudioItem) so the offline library can
// render without the server. `size` is the integrity signal checked on launch.
export interface OfflineTrackRecord<M = unknown> {
  trackId: string;
  size: number;
  contentType: string;
  downloadedAt: number;
  metadata: M;
}

// A mutation that could not reach the server (made while offline) and must be
// replayed once connectivity returns. `payload` is kind-specific.
export interface OutboxEntry {
  id?: number;
  kind: string;
  createdAt: number;
  payload: Record<string, unknown>;
}

export interface StorageEstimateResult {
  usage: number;
  quota: number;
}

function getIndexedDb(): IDBFactory | null {
  const idb = (globalThis as { indexedDB?: IDBFactory }).indexedDB;
  return idb ?? null;
}

async function promisifyRequest<T>(request: IDBRequest<T>): Promise<T> {
  return new Promise<T>((resolve, reject) => {
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error ?? new Error('IndexedDB request failed'));
  });
}

async function promisifyTransaction(tx: IDBTransaction): Promise<void> {
  return new Promise<void>((resolve, reject) => {
    tx.oncomplete = () => resolve();
    tx.onerror = () => reject(tx.error ?? new Error('IndexedDB transaction failed'));
    tx.onabort = () => reject(tx.error ?? new Error('IndexedDB transaction aborted'));
  });
}

export class IndexedDbOfflineStore<M = unknown> {
  private db: IDBDatabase | null = null;
  private openPromise: Promise<IDBDatabase> | null = null;

  isSupported(): boolean {
    return getIndexedDb() !== null;
  }

  private async open(): Promise<IDBDatabase> {
    if (this.db) return this.db;
    if (this.openPromise) return this.openPromise;

    const idb = getIndexedDb();
    if (!idb) {
      throw new Error('IndexedDB is not available in this environment');
    }

    this.openPromise = new Promise<IDBDatabase>((resolve, reject) => {
      const request = idb.open(DB_NAME, DB_VERSION);
      request.onupgradeneeded = () => {
        const db = request.result;
        if (!db.objectStoreNames.contains(STORE_TRACKS)) {
          db.createObjectStore(STORE_TRACKS, { keyPath: 'trackId' });
        }
        if (!db.objectStoreNames.contains(STORE_BLOBS)) {
          db.createObjectStore(STORE_BLOBS, { keyPath: 'trackId' });
        }
        if (!db.objectStoreNames.contains(STORE_OUTBOX)) {
          db.createObjectStore(STORE_OUTBOX, { keyPath: 'id', autoIncrement: true });
        }
        // v2: album/track cover art so the offline library renders artwork
        // without the network. Idempotent create handles the v1 → v2 upgrade.
        if (!db.objectStoreNames.contains(STORE_COVERS)) {
          db.createObjectStore(STORE_COVERS, { keyPath: 'key' });
        }
      };
      request.onsuccess = () => {
        const db = request.result;
        // A version change requested elsewhere (e.g. another tab) closes us so
        // the upgrade can proceed; drop the handle and reopen on next use.
        db.onversionchange = () => {
          db.close();
          this.db = null;
          this.openPromise = null;
        };
        resolve(db);
      };
      request.onerror = () => reject(request.error ?? new Error('Failed to open offline database'));
    });

    try {
      this.db = await this.openPromise;
      return this.db;
    } finally {
      this.openPromise = null;
    }
  }

  async putTrack(record: OfflineTrackRecord<M>, blob: Blob): Promise<void> {
    const db = await this.open();
    const tx = db.transaction([STORE_TRACKS, STORE_BLOBS], 'readwrite');
    tx.objectStore(STORE_TRACKS).put(record);
    tx.objectStore(STORE_BLOBS).put({ trackId: record.trackId, blob });
    await promisifyTransaction(tx);
  }

  async getTrack(trackId: string): Promise<OfflineTrackRecord<M> | null> {
    const db = await this.open();
    const tx = db.transaction(STORE_TRACKS, 'readonly');
    const result = await promisifyRequest(
      tx.objectStore(STORE_TRACKS).get(trackId) as IDBRequest<OfflineTrackRecord<M> | undefined>
    );
    return result ?? null;
  }

  async getBlob(trackId: string): Promise<Blob | null> {
    const db = await this.open();
    const tx = db.transaction(STORE_BLOBS, 'readonly');
    const result = await promisifyRequest(
      tx.objectStore(STORE_BLOBS).get(trackId) as IDBRequest<
        { trackId: string; blob: Blob } | undefined
      >
    );
    return result?.blob ?? null;
  }

  async hasTrack(trackId: string): Promise<boolean> {
    const db = await this.open();
    const tx = db.transaction(STORE_TRACKS, 'readonly');
    const key = await promisifyRequest<IDBValidKey | undefined>(
      tx.objectStore(STORE_TRACKS).getKey(trackId)
    );
    return key !== undefined;
  }

  async listTracks(): Promise<OfflineTrackRecord<M>[]> {
    const db = await this.open();
    const tx = db.transaction(STORE_TRACKS, 'readonly');
    const result = await promisifyRequest(
      tx.objectStore(STORE_TRACKS).getAll() as IDBRequest<OfflineTrackRecord<M>[]>
    );
    return result ?? [];
  }

  async deleteTrack(trackId: string): Promise<void> {
    const db = await this.open();
    const tx = db.transaction([STORE_TRACKS, STORE_BLOBS], 'readwrite');
    tx.objectStore(STORE_TRACKS).delete(trackId);
    tx.objectStore(STORE_BLOBS).delete(trackId);
    await promisifyTransaction(tx);
  }

  async clearTracks(): Promise<void> {
    const db = await this.open();
    const tx = db.transaction([STORE_TRACKS, STORE_BLOBS, STORE_COVERS], 'readwrite');
    tx.objectStore(STORE_TRACKS).clear();
    tx.objectStore(STORE_BLOBS).clear();
    tx.objectStore(STORE_COVERS).clear();
    await promisifyTransaction(tx);
  }

  async getUsage(): Promise<number> {
    const tracks = await this.listTracks();
    return tracks.reduce((total, track) => total + (track.size || 0), 0);
  }

  async putCover(key: string, blob: Blob): Promise<void> {
    const db = await this.open();
    const tx = db.transaction(STORE_COVERS, 'readwrite');
    tx.objectStore(STORE_COVERS).put({ key, blob });
    await promisifyTransaction(tx);
  }

  async getCover(key: string): Promise<Blob | null> {
    const db = await this.open();
    const tx = db.transaction(STORE_COVERS, 'readonly');
    const result = await promisifyRequest(
      tx.objectStore(STORE_COVERS).get(key) as IDBRequest<{ key: string; blob: Blob } | undefined>
    );
    return result?.blob ?? null;
  }

  async deleteCover(key: string): Promise<void> {
    const db = await this.open();
    const tx = db.transaction(STORE_COVERS, 'readwrite');
    tx.objectStore(STORE_COVERS).delete(key);
    await promisifyTransaction(tx);
  }

  async enqueue(entry: Omit<OutboxEntry, 'id'>): Promise<void> {
    const db = await this.open();
    const tx = db.transaction(STORE_OUTBOX, 'readwrite');
    tx.objectStore(STORE_OUTBOX).add(entry);
    await promisifyTransaction(tx);
  }

  async listOutbox(): Promise<OutboxEntry[]> {
    const db = await this.open();
    const tx = db.transaction(STORE_OUTBOX, 'readonly');
    const result = await promisifyRequest(
      tx.objectStore(STORE_OUTBOX).getAll() as IDBRequest<OutboxEntry[]>
    );
    return result ?? [];
  }

  async deleteOutbox(id: number): Promise<void> {
    const db = await this.open();
    const tx = db.transaction(STORE_OUTBOX, 'readwrite');
    tx.objectStore(STORE_OUTBOX).delete(id);
    await promisifyTransaction(tx);
  }

  async clearAll(): Promise<void> {
    const db = await this.open();
    const tx = db.transaction([STORE_TRACKS, STORE_BLOBS, STORE_OUTBOX, STORE_COVERS], 'readwrite');
    tx.objectStore(STORE_TRACKS).clear();
    tx.objectStore(STORE_BLOBS).clear();
    tx.objectStore(STORE_OUTBOX).clear();
    tx.objectStore(STORE_COVERS).clear();
    await promisifyTransaction(tx);
  }

  close(): void {
    if (this.db) {
      this.db.close();
      this.db = null;
    }
    this.openPromise = null;
  }
}

// Ask the browser to keep our site data out of the eviction-under-pressure pool.
// The browser may grant or deny; treat false as best-effort, never an error.
export async function requestPersistentStorage(): Promise<boolean> {
  try {
    if (navigator.storage?.persist) {
      return await navigator.storage.persist();
    }
  } catch (error) {
    log.warn('persist() request failed', { error: String(error) });
  }
  return false;
}

export async function isStoragePersisted(): Promise<boolean> {
  try {
    if (navigator.storage?.persisted) {
      return await navigator.storage.persisted();
    }
  } catch {
    // ignored
  }
  return false;
}

export async function estimateStorage(): Promise<StorageEstimateResult | null> {
  try {
    if (navigator.storage?.estimate) {
      const estimate = await navigator.storage.estimate();
      return { usage: estimate.usage ?? 0, quota: estimate.quota ?? 0 };
    }
  } catch {
    // ignored
  }
  return null;
}
