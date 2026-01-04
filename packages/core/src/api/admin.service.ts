import { BaseService } from './base-api.service.js';

export interface CacheStats {
  imageCache: {
    size: number;
    hitCount: number;
    missCount: number;
    hitRate: number;
    evictionCount: number;
    loadSuccessCount: number;
    loadFailureCount: number;
    averageLoadPenaltyMs: number;
  };
}

export interface CacheClearResult {
  cleared: boolean;
  entriesCleared: number;
  message?: string;
  itemId?: string;
}

export class AdminService extends BaseService {
  async getCacheStats(): Promise<CacheStats> {
    this.requireAuth();
    const result = await this.client.get<CacheStats>('/Admin/Cache/Stats');
    if (!result) {
      throw new Error('Failed to get cache stats');
    }
    return result;
  }

  async clearAllCaches(): Promise<CacheClearResult> {
    this.requireAuth();
    const result = await this.client.delete<CacheClearResult>('/Admin/Cache');
    if (!result) {
      throw new Error('Failed to clear caches');
    }
    return result;
  }

  async clearItemCache(itemId: string): Promise<CacheClearResult> {
    this.requireAuth();
    const result = await this.client.delete<CacheClearResult>(`/Admin/Cache/Images/${itemId}`);
    if (!result) {
      throw new Error('Failed to clear item cache');
    }
    return result;
  }
}
