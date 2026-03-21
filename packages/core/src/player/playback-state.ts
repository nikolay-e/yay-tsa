import { type MediaServerClient } from '../api/api.client.js';
import {
  type PlaybackProgressInfo,
  type PlaybackStartInfo,
  type PlaybackStopInfo,
} from '../internal/models/types.js';
import { secondsToTicks } from '../internal/config/constants.js';

export class PlaybackReporter {
  constructor(private readonly client: MediaServerClient) {}

  async reportStart(itemId: string): Promise<void> {
    if (!this.client.isAuthenticated()) {
      throw new Error('Not authenticated');
    }

    const info: PlaybackStartInfo = {
      ItemId: itemId,
      PositionTicks: 0,
      IsPaused: false,
      PlayMethod: 'DirectPlay',
      CanSeek: true,
      VolumeLevel: 100,
      IsMuted: false,
    };

    await this.client.post('/Sessions/Playing', info);
  }

  async reportProgress(itemId: string, positionSeconds: number, isPaused: boolean): Promise<void> {
    if (!this.client.isAuthenticated()) {
      throw new Error('Not authenticated');
    }

    const info: PlaybackProgressInfo = {
      ItemId: itemId,
      PositionTicks: secondsToTicks(positionSeconds),
      IsPaused: isPaused,
      PlayMethod: 'DirectPlay',
      VolumeLevel: 100,
      IsMuted: false,
    };

    await this.client.post('/Sessions/Playing/Progress', info);
  }

  async reportStopped(itemId: string, positionSeconds: number): Promise<void> {
    if (!this.client.isAuthenticated()) {
      throw new Error('Not authenticated');
    }

    const info: PlaybackStopInfo = {
      ItemId: itemId,
      PositionTicks: secondsToTicks(positionSeconds),
    };

    await this.client.post('/Sessions/Playing/Stopped', info);
  }
}
