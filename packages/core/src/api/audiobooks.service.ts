import { type AudioItem, MediaServerError } from '../internal/models/types.js';
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
    try {
      const result = await this.client.get<AudiobookEntry[]>('/v1/me/audiobooks');
      return result ?? [];
    } catch (error) {
      // A 404 means the server does not expose the audiobooks endpoint yet (e.g. an older
      // backend, or a frontend-ahead-of-backend deploy window). Degrade to an empty shelf
      // instead of surfacing an error screen.
      if (error instanceof MediaServerError && error.statusCode === 404) return [];
      throw error;
    }
  }

  async markFinished(itemId: string): Promise<AudiobookResume | undefined> {
    return this.client.post<AudiobookResume>(`/v1/me/audiobooks/${itemId}/finished`);
  }

  async restart(itemId: string): Promise<AudiobookResume | undefined> {
    return this.client.post<AudiobookResume>(`/v1/me/audiobooks/${itemId}/restart`);
  }
}
