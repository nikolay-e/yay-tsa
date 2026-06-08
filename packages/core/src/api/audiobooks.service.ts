import type { AudioItem } from '../internal/models/types.js';
import { BaseService } from './base-api.service.js';

export type AudiobookStatus = 'not_started' | 'in_progress' | 'finished' | 'relistening';

export interface AudiobookResume {
  positionMs: number;
  runTimeMs: number;
  status: AudiobookStatus;
  progressPercent: number;
  remainingMs: number;
  updatedAt: string;
}

export interface AudiobookEntry {
  item: AudioItem;
  resume: AudiobookResume;
}

export class AudiobooksService extends BaseService {
  async list(): Promise<AudiobookEntry[]> {
    const result = await this.client.get<AudiobookEntry[]>('/v1/me/audiobooks');
    return result ?? [];
  }

  async markFinished(itemId: string): Promise<AudiobookResume | undefined> {
    return this.client.post<AudiobookResume>(`/v1/me/audiobooks/${itemId}/finished`);
  }

  async restart(itemId: string): Promise<AudiobookResume | undefined> {
    return this.client.post<AudiobookResume>(`/v1/me/audiobooks/${itemId}/restart`);
  }
}
