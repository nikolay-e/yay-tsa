/**
 * IndexedDB Cache Implementation
 * Browser-based persistent cache using IndexedDB
 */

import { type ICache, type CacheConfig, type CacheEntry } from './cache.interface.js';

export class IndexedDBCache implements ICache {
  private dbName: string;
  private storeName: string;
  private version: string;
  private db: IDBDatabase | null = null;
  private initPromise: Promise<void> | null = null;
  private isClosing = false;

  constructor(config: CacheConfig) {
    this.dbName = `yaytsa-cache-${config.name}`;
    this.storeName = 'cache-entries';
    this.version = config.version;
  }

  private resetConnection(): void {
    this.db = null;
    this.initPromise = null;
    this.isClosing = false;
  }

  private async init(): Promise<void> {
    if (this.db && !this.isClosing) {
      return;
    }

    if (this.isClosing) {
      this.resetConnection();
    }

    if (this.initPromise) {
      return this.initPromise;
    }

    this.initPromise = new Promise((resolve, reject) => {
      const request = indexedDB.open(this.dbName, 1);

      request.onerror = () => {
        this.resetConnection();
        reject(new Error(`Failed to open IndexedDB: ${request.error?.message}`));
      };

      request.onsuccess = () => {
        this.db = request.result;

        this.db.onversionchange = () => {
          this.isClosing = true;
          if (this.db) {
            this.db.close();
          }
          this.resetConnection();
        };

        this.db.onclose = () => {
          this.resetConnection();
        };

        resolve();
      };

      request.onupgradeneeded = event => {
        const db = (event.target as IDBOpenDBRequest).result;

        if (!db.objectStoreNames.contains(this.storeName)) {
          const store = db.createObjectStore(this.storeName, { keyPath: 'key' });
          store.createIndex('timestamp', 'timestamp', { unique: false });
        }
      };
    });

    return this.initPromise;
  }

  private isConnectionClosingError(error: unknown): boolean {
    if (error instanceof DOMException) {
      return error.name === 'InvalidStateError' && error.message.includes('closing');
    }
    return false;
  }

  private async withRetry<T>(operation: () => Promise<T>, retries = 1): Promise<T> {
    try {
      return await operation();
    } catch (error) {
      if (retries > 0 && this.isConnectionClosingError(error)) {
        this.resetConnection();
        await this.init();
        return this.withRetry(operation, retries - 1);
      }
      throw error;
    }
  }

  async get<T>(key: string): Promise<T | null> {
    return this.withRetry(async () => {
      await this.init();

      if (!this.db) {
        return null;
      }

      const db = this.db;
      return new Promise((resolve, reject) => {
        const transaction = db.transaction([this.storeName], 'readonly');
        const store = transaction.objectStore(this.storeName);
        const request = store.get(key);

        request.onsuccess = () => {
          const entry = request.result as CacheEntry<T> | undefined;

          if (!entry) {
            resolve(null);
            return;
          }

          const now = Date.now();
          const expiresAt = entry.timestamp + entry.ttl;
          if (now > expiresAt) {
            void this.delete(key);
            resolve(null);
            return;
          }

          if (entry.version !== this.version) {
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
    });
  }

  async set<T>(key: string, value: T, ttl: number): Promise<void> {
    return this.withRetry(async () => {
      await this.init();

      if (!this.db) {
        throw new Error('IndexedDB not initialized');
      }

      const db = this.db;
      const entry: CacheEntry<T> = {
        key,
        data: value,
        timestamp: Date.now(),
        ttl,
        version: this.version,
      };

      return new Promise((resolve, reject) => {
        const transaction = db.transaction([this.storeName], 'readwrite');
        const store = transaction.objectStore(this.storeName);
        const request = store.put(entry);

        request.onsuccess = () => {
          resolve();
        };

        request.onerror = () => {
          reject(new Error(`Failed to set cache entry: ${request.error?.message}`));
        };
      });
    });
  }

  async delete(key: string): Promise<void> {
    return this.withRetry(async () => {
      await this.init();

      if (!this.db) {
        return;
      }

      const db = this.db;
      return new Promise((resolve, reject) => {
        const transaction = db.transaction([this.storeName], 'readwrite');
        const store = transaction.objectStore(this.storeName);
        const request = store.delete(key);

        request.onsuccess = () => {
          resolve();
        };

        request.onerror = () => {
          reject(new Error(`Failed to delete cache entry: ${request.error?.message}`));
        };
      });
    });
  }

  async clear(): Promise<void> {
    return this.withRetry(async () => {
      await this.init();

      if (!this.db) {
        return;
      }

      const db = this.db;
      return new Promise((resolve, reject) => {
        const transaction = db.transaction([this.storeName], 'readwrite');
        const store = transaction.objectStore(this.storeName);
        const request = store.clear();

        request.onsuccess = () => {
          resolve();
        };

        request.onerror = () => {
          reject(new Error(`Failed to clear cache: ${request.error?.message}`));
        };
      });
    });
  }

  async has(key: string): Promise<boolean> {
    const value = await this.get(key);
    return value !== null;
  }

  async keys(): Promise<string[]> {
    return this.withRetry(async () => {
      await this.init();

      if (!this.db) {
        return [];
      }

      const db = this.db;
      return new Promise((resolve, reject) => {
        const transaction = db.transaction([this.storeName], 'readonly');
        const store = transaction.objectStore(this.storeName);
        const request = store.getAllKeys();

        request.onsuccess = () => {
          resolve(request.result as string[]);
        };

        request.onerror = () => {
          reject(new Error(`Failed to get cache keys: ${request.error?.message}`));
        };
      });
    });
  }

  async cleanup(): Promise<void> {
    return this.withRetry(async () => {
      await this.init();

      if (!this.db) {
        return;
      }

      const db = this.db;
      const now = Date.now();

      return new Promise((resolve, reject) => {
        const transaction = db.transaction([this.storeName], 'readwrite');
        const store = transaction.objectStore(this.storeName);
        const index = store.index('timestamp');
        const request = index.openCursor();

        request.onsuccess = event => {
          const cursor = (event.target as IDBRequest<IDBCursorWithValue>).result;

          if (cursor) {
            const entry = cursor.value as CacheEntry<unknown>;
            const expiresAt = entry.timestamp + entry.ttl;

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
    });
  }

  close(): void {
    if (this.db) {
      this.isClosing = true;
      this.db.close();
      this.resetConnection();
    }
  }
}
