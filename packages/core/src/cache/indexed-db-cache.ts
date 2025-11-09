/**
 * IndexedDB Cache Implementation
 * Browser-based persistent cache using IndexedDB
 */

import { ICache, CacheConfig, CacheEntry } from './cache-interface.js';

export class IndexedDBCache implements ICache {
  private dbName: string;
  private storeName: string;
  private version: string;
  private db: IDBDatabase | null = null;
  private initPromise: Promise<void> | null = null;

  constructor(config: CacheConfig) {
    this.dbName = `yaytsa-cache-${config.name}`;
    this.storeName = 'cache-entries';
    this.version = config.version;
  }

  /**
   * Initialize IndexedDB connection
   */
  private async init(): Promise<void> {
    if (this.db) {
      return; // Already initialized
    }

    // Prevent multiple concurrent initializations
    if (this.initPromise) {
      return this.initPromise;
    }

    this.initPromise = new Promise((resolve, reject) => {
      const request = indexedDB.open(this.dbName, 1);

      request.onerror = () => {
        reject(new Error(`Failed to open IndexedDB: ${request.error?.message}`));
      };

      request.onsuccess = () => {
        this.db = request.result;
        resolve();
      };

      request.onupgradeneeded = event => {
        const db = (event.target as IDBOpenDBRequest).result;

        // Create object store if it doesn't exist
        if (!db.objectStoreNames.contains(this.storeName)) {
          const store = db.createObjectStore(this.storeName, { keyPath: 'key' });
          store.createIndex('timestamp', 'timestamp', { unique: false });
        }
      };
    });

    return this.initPromise;
  }

  /**
   * Get value from cache
   */
  async get<T>(key: string): Promise<T | null> {
    await this.init();

    if (!this.db) {
      return null;
    }

    return new Promise((resolve, reject) => {
      const transaction = this.db!.transaction([this.storeName], 'readonly');
      const store = transaction.objectStore(this.storeName);
      const request = store.get(key);

      request.onsuccess = () => {
        const entry = request.result as CacheEntry<T> | undefined;

        if (!entry) {
          resolve(null);
          return;
        }

        // Check if expired
        const now = Date.now();
        const expiresAt = entry.timestamp + entry.ttl;
        if (now > expiresAt) {
          // Expired - delete and return null
          void this.delete(key);
          resolve(null);
          return;
        }

        // Check version mismatch
        if (entry.version !== this.version) {
          // Version mismatch - delete and return null
          void this.delete(key);
          resolve(null);
          return;
        }

        resolve(entry.data);
      };

      request.onerror = () => {
        reject(new Error(`Failed to get cache entry: ${request.error?.message}`));
      };
    });
  }

  /**
   * Set value in cache with TTL
   */
  async set<T>(key: string, value: T, ttl: number): Promise<void> {
    await this.init();

    if (!this.db) {
      throw new Error('IndexedDB not initialized');
    }

    const entry: CacheEntry<T> = {
      key,
      data: value,
      timestamp: Date.now(),
      ttl,
      version: this.version,
    };

    return new Promise((resolve, reject) => {
      const transaction = this.db!.transaction([this.storeName], 'readwrite');
      const store = transaction.objectStore(this.storeName);
      const request = store.put(entry);

      request.onsuccess = () => {
        resolve();
      };

      request.onerror = () => {
        reject(new Error(`Failed to set cache entry: ${request.error?.message}`));
      };
    });
  }

  /**
   * Delete specific key from cache
   */
  async delete(key: string): Promise<void> {
    await this.init();

    if (!this.db) {
      return;
    }

    return new Promise((resolve, reject) => {
      const transaction = this.db!.transaction([this.storeName], 'readwrite');
      const store = transaction.objectStore(this.storeName);
      const request = store.delete(key);

      request.onsuccess = () => {
        resolve();
      };

      request.onerror = () => {
        reject(new Error(`Failed to delete cache entry: ${request.error?.message}`));
      };
    });
  }

  /**
   * Clear all cache entries
   */
  async clear(): Promise<void> {
    await this.init();

    if (!this.db) {
      return;
    }

    return new Promise((resolve, reject) => {
      const transaction = this.db!.transaction([this.storeName], 'readwrite');
      const store = transaction.objectStore(this.storeName);
      const request = store.clear();

      request.onsuccess = () => {
        resolve();
      };

      request.onerror = () => {
        reject(new Error(`Failed to clear cache: ${request.error?.message}`));
      };
    });
  }

  /**
   * Check if key exists and not expired
   */
  async has(key: string): Promise<boolean> {
    const value = await this.get(key);
    return value !== null;
  }

  /**
   * Get all keys in cache
   */
  async keys(): Promise<string[]> {
    await this.init();

    if (!this.db) {
      return [];
    }

    return new Promise((resolve, reject) => {
      const transaction = this.db!.transaction([this.storeName], 'readonly');
      const store = transaction.objectStore(this.storeName);
      const request = store.getAllKeys();

      request.onsuccess = () => {
        resolve(request.result as string[]);
      };

      request.onerror = () => {
        reject(new Error(`Failed to get cache keys: ${request.error?.message}`));
      };
    });
  }

  /**
   * Remove expired entries (cleanup)
   */
  async cleanup(): Promise<void> {
    await this.init();

    if (!this.db) {
      return;
    }

    const now = Date.now();

    return new Promise((resolve, reject) => {
      const transaction = this.db!.transaction([this.storeName], 'readwrite');
      const store = transaction.objectStore(this.storeName);
      const index = store.index('timestamp');
      const request = index.openCursor();

      request.onsuccess = event => {
        const cursor = (event.target as IDBRequest<IDBCursorWithValue>).result;

        if (cursor) {
          const entry = cursor.value as CacheEntry<unknown>;
          const expiresAt = entry.timestamp + entry.ttl;

          // Delete if expired or version mismatch
          if (now > expiresAt || entry.version !== this.version) {
            cursor.delete();
          }

          cursor.continue();
        } else {
          resolve();
        }
      };

      request.onerror = () => {
        reject(new Error(`Failed to cleanup cache: ${request.error?.message}`));
      };
    });
  }

  /**
   * Close database connection
   */
  close(): void {
    if (this.db) {
      this.db.close();
      this.db = null;
      this.initPromise = null;
    }
  }
}
