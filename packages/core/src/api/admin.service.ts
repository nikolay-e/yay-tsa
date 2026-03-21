import { MediaServerError } from '../internal/models/types.js';
import { BaseService } from './base-api.service.js';

export interface UserSummary {
  Id: string;
  Username: string;
  DisplayName: string | null;
  IsAdmin: boolean;
  IsActive: boolean;
  CreatedAt: string;
  LastLoginAt: string | null;
}

export interface CreateUserRequest {
  Username: string;
  DisplayName?: string;
  IsAdmin: boolean;
}

export interface CreateUserResult {
  user: UserSummary;
  initialPassword: string;
}

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

export interface LibraryRescanResult {
  success: boolean;
  message: string;
  scanInProgress?: boolean;
}

export interface ScanStatus {
  scanInProgress: boolean;
}

export class AdminService extends BaseService {
  async getCacheStats(): Promise<CacheStats> {
    this.requireAuth();
    const result = await this.client.get<CacheStats>('/Admin/Cache/Stats');
    if (!result) {
      throw new MediaServerError('Failed to get cache stats');
    }
    return result;
  }

  async clearAllCaches(): Promise<CacheClearResult> {
    this.requireAuth();
    const result = await this.client.delete<CacheClearResult>('/Admin/Cache');
    if (!result) {
      throw new MediaServerError('Failed to clear caches');
    }
    return result;
  }

  async clearItemCache(itemId: string): Promise<CacheClearResult> {
    this.requireAuth();
    const result = await this.client.delete<CacheClearResult>(`/Admin/Cache/Images/${itemId}`);
    if (!result) {
      throw new MediaServerError('Failed to clear item cache');
    }
    return result;
  }

  async rescanLibrary(): Promise<LibraryRescanResult> {
    this.requireAuth();
    const result = await this.client.post<LibraryRescanResult>('/Admin/Library/Rescan', {});
    if (!result) {
      throw new MediaServerError('Failed to trigger library rescan');
    }
    return result;
  }

  async getScanStatus(): Promise<ScanStatus> {
    this.requireAuth();
    const result = await this.client.get<ScanStatus>('/Admin/Library/ScanStatus');
    if (!result) {
      throw new MediaServerError('Failed to get scan status');
    }
    return result;
  }

  async listUsers(): Promise<UserSummary[]> {
    this.requireAuth();
    const result = await this.client.get<UserSummary[]>('/Admin/Users');
    return result ?? [];
  }

  async createUser(request: CreateUserRequest): Promise<CreateUserResult> {
    this.requireAuth();
    const result = await this.client.post<CreateUserResult>('/Admin/Users', request);
    if (!result) throw new MediaServerError('Failed to create user');
    return result;
  }

  async deleteUser(userId: string): Promise<void> {
    this.requireAuth();
    await this.client.delete<void>(`/Admin/Users/${userId}`);
  }

  async resetPassword(userId: string): Promise<string> {
    this.requireAuth();
    const result = await this.client.post<{ newPassword: string }>(
      `/Admin/Users/${userId}/ResetPassword`,
      {}
    );
    if (!result) throw new MediaServerError('Failed to reset password');
    return result.newPassword;
  }
}
