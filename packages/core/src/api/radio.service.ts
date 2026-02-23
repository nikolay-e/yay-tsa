import { BaseService } from './base-api.service.js';
import type { AudioItem } from '../internal/models/types.js';

export interface RadioFilters {
  mood?: string;
  language?: string;
  minEnergy?: number;
  maxEnergy?: number;
}

export interface RadioFiltersResponse {
  moods: string[];
  languages: string[];
  totalTracks: number;
  analyzedTracks: number;
}

export interface AnalysisStats {
  total: number;
  analyzed: number;
  unanalyzed: number;
  batchRunning: boolean;
}

interface QueryResult {
  Items: AudioItem[];
  TotalRecordCount: number;
}

export class RadioService extends BaseService {
  async getMyWave(filters: RadioFilters = {}, count = 20): Promise<AudioItem[]> {
    this.requireAuth();
    const params: Record<string, unknown> = { count };
    if (filters.mood) params.mood = filters.mood;
    if (filters.language) params.language = filters.language;
    if (filters.minEnergy != null) params.minEnergy = filters.minEnergy;
    if (filters.maxEnergy != null) params.maxEnergy = filters.maxEnergy;

    const result = await this.client.get<QueryResult>('/Radio/MyWave', params);
    return result?.Items ?? [];
  }

  async getAvailableFilters(): Promise<RadioFiltersResponse> {
    this.requireAuth();
    const result = await this.client.get<RadioFiltersResponse>('/Radio/MyWave/Filters');
    return result ?? { moods: [], languages: [], totalTracks: 0, analyzedTracks: 0 };
  }

  async getAnalysisStats(): Promise<AnalysisStats> {
    this.requireAuth();
    const result = await this.client.get<AnalysisStats>('/Radio/Analysis/Stats');
    if (!result) throw new Error('Failed to get analysis stats');
    return result;
  }

  async startBatchAnalysis(): Promise<void> {
    this.requireAuth();
    await this.client.post('/Radio/Analysis/Start', {});
  }

  async stopBatchAnalysis(): Promise<void> {
    this.requireAuth();
    await this.client.post('/Radio/Analysis/Stop', {});
  }
}
