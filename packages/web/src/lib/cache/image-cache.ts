/**
 * Image Blob Cache
 * Stores image blobs in IndexedDB for faster loading
 */

export type ImageSize = 'small' | 'medium' | 'large';

interface ImageCacheEntry {
  key: string; // Composite key: ${itemId}-${imageType}-${size}
  itemId: string;
  imageType: string;
  size: ImageSize;
  blob: Blob;
  timestamp: number;
  url?: string; // Object URL (created on demand, not stored)
}

export class ImageCache {
  private dbName = 'yaytsa-image-cache';
  private storeName = 'images';
  private db: IDBDatabase | null = null;
  private initPromise: Promise<void> | null = null;
  private objectUrls: Map<string, string> = new Map(); // Track created object URLs for cleanup

  /**
   * Initialize IndexedDB connection
   */
  private async init(): Promise<void> {
    if (this.db) {
      return;
    }

    if (this.initPromise) {
      return this.initPromise;
    }

    this.initPromise = new Promise((resolve, reject) => {
      const request = indexedDB.open(this.dbName, 1);

      request.onerror = () => {
        reject(new Error(`Failed to open image cache DB: ${request.error?.message}`));
      };

      request.onsuccess = () => {
        this.db = request.result;
        resolve();
      };

      request.onupgradeneeded = event => {
        const db = (event.target as IDBOpenDBRequest).result;

        if (!db.objectStoreNames.contains(this.storeName)) {
          const store = db.createObjectStore(this.storeName, { keyPath: 'key' });
          store.createIndex('timestamp', 'timestamp', { unique: false });
          store.createIndex('itemId', 'itemId', { unique: false });
        }
      };
    });

    return this.initPromise;
  }

  /**
   * Build cache key from itemId, imageType, and size
   */
  private buildKey(itemId: string, imageType: string, size: ImageSize): string {
    return `${itemId}-${imageType}-${size}`;
  }

  /**
   * Get cached image blob
   */
  async get(itemId: string, imageType: string, size: ImageSize): Promise<Blob | null> {
    await this.init();

    if (!this.db) {
      return null;
    }

    const key = this.buildKey(itemId, imageType, size);

    return new Promise((resolve, reject) => {
      const transaction = this.db!.transaction([this.storeName], 'readonly');
      const store = transaction.objectStore(this.storeName);
      const request = store.get(key);

      request.onsuccess = () => {
        const entry = request.result as ImageCacheEntry | undefined;
        resolve(entry?.blob || null);
      };

      request.onerror = () => {
        reject(new Error(`Failed to get image from cache: ${request.error?.message}`));
      };
    });
  }

  /**
   * Store image blob in cache
   */
  async set(itemId: string, imageType: string, size: ImageSize, blob: Blob): Promise<void> {
    await this.init();

    if (!this.db) {
      throw new Error('Image cache DB not initialized');
    }

    const key = this.buildKey(itemId, imageType, size);
    const entry: ImageCacheEntry = {
      key,
      itemId,
      imageType,
      size,
      blob,
      timestamp: Date.now(),
    };

    return new Promise((resolve, reject) => {
      const transaction = this.db!.transaction([this.storeName], 'readwrite');
      const store = transaction.objectStore(this.storeName);
      const request = store.put(entry);

      request.onsuccess = () => {
        resolve();
      };

      request.onerror = () => {
        reject(new Error(`Failed to store image in cache: ${request.error?.message}`));
      };
    });
  }

  /**
   * Get or create object URL for cached image
   * Returns null if not in cache, caller should fetch and cache
   */
  async getObjectUrl(itemId: string, imageType: string, size: ImageSize): Promise<string | null> {
    const key = this.buildKey(itemId, imageType, size);

    // Check if we already created an object URL for this
    if (this.objectUrls.has(key)) {
      return this.objectUrls.get(key)!;
    }

    // Get blob from cache
    const blob = await this.get(itemId, imageType, size);
    if (!blob) {
      return null;
    }

    // Create object URL
    const url = URL.createObjectURL(blob);
    this.objectUrls.set(key, url);

    return url;
  }

  /**
   * Fetch image, cache it, and return object URL
   */
  async fetchAndCache(
    itemId: string,
    imageType: string,
    size: ImageSize,
    imageUrl: string
  ): Promise<string> {
    // Fetch image
    const response = await fetch(imageUrl);
    if (!response.ok) {
      throw new Error(`Failed to fetch image: ${response.statusText}`);
    }

    const blob = await response.blob();

    // Store in cache
    await this.set(itemId, imageType, size, blob);

    // Create and return object URL
    const key = this.buildKey(itemId, imageType, size);
    const url = URL.createObjectURL(blob);
    this.objectUrls.set(key, url);

    return url;
  }

  /**
   * Revoke object URL to free memory
   */
  revokeObjectUrl(itemId: string, imageType: string, size: ImageSize): void {
    const key = this.buildKey(itemId, imageType, size);
    const url = this.objectUrls.get(key);

    if (url) {
      URL.revokeObjectURL(url);
      this.objectUrls.delete(key);
    }
  }

  /**
   * Revoke all object URLs (cleanup on unmount)
   */
  revokeAllObjectUrls(): void {
    for (const url of this.objectUrls.values()) {
      URL.revokeObjectURL(url);
    }
    this.objectUrls.clear();
  }

  /**
   * Clear all cached images
   */
  async clear(): Promise<void> {
    await this.init();

    if (!this.db) {
      return;
    }

    // Revoke all object URLs first
    this.revokeAllObjectUrls();

    return new Promise((resolve, reject) => {
      const transaction = this.db!.transaction([this.storeName], 'readwrite');
      const store = transaction.objectStore(this.storeName);
      const request = store.clear();

      request.onsuccess = () => {
        resolve();
      };

      request.onerror = () => {
        reject(new Error(`Failed to clear image cache: ${request.error?.message}`));
      };
    });
  }

  /**
   * Delete images for specific item
   */
  async deleteItem(itemId: string): Promise<void> {
    await this.init();

    if (!this.db) {
      return;
    }

    return new Promise((resolve, reject) => {
      const transaction = this.db!.transaction([this.storeName], 'readwrite');
      const store = transaction.objectStore(this.storeName);
      const index = store.index('itemId');
      const request = index.openCursor(IDBKeyRange.only(itemId));

      request.onsuccess = event => {
        const cursor = (event.target as IDBRequest<IDBCursorWithValue>).result;

        if (cursor) {
          const entry = cursor.value as ImageCacheEntry;

          // Revoke object URL if exists
          this.revokeObjectUrl(entry.itemId, entry.imageType, entry.size);

          // Delete from DB
          cursor.delete();
          cursor.continue();
        } else {
          resolve();
        }
      };

      request.onerror = () => {
        reject(new Error(`Failed to delete images: ${request.error?.message}`));
      };
    });
  }

  /**
   * Cleanup old images (older than 7 days)
   */
  async cleanup(): Promise<void> {
    await this.init();

    if (!this.db) {
      return;
    }

    const sevenDaysAgo = Date.now() - 7 * 24 * 60 * 60 * 1000;

    return new Promise((resolve, reject) => {
      const transaction = this.db!.transaction([this.storeName], 'readwrite');
      const store = transaction.objectStore(this.storeName);
      const index = store.index('timestamp');
      const request = index.openCursor(IDBKeyRange.upperBound(sevenDaysAgo));

      request.onsuccess = event => {
        const cursor = (event.target as IDBRequest<IDBCursorWithValue>).result;

        if (cursor) {
          const entry = cursor.value as ImageCacheEntry;

          // Revoke object URL if exists
          this.revokeObjectUrl(entry.itemId, entry.imageType, entry.size);

          // Delete old entry
          cursor.delete();
          cursor.continue();
        } else {
          resolve();
        }
      };

      request.onerror = () => {
        reject(new Error(`Failed to cleanup image cache: ${request.error?.message}`));
      };
    });
  }

  /**
   * Close database connection
   */
  close(): void {
    this.revokeAllObjectUrls();

    if (this.db) {
      this.db.close();
      this.db = null;
      this.initPromise = null;
    }
  }
}
