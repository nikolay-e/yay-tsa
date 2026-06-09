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

  async reportProgress(
    itemId: string,
    positionSeconds: number,
    isPaused: boolean,
    eventName?: PlaybackProgressInfo['EventName']
  ): Promise<void> {
    if (!this.client.isAuthenticated()) {
      throw new Error('Not authenticated');
    }

    await this.client.post(
      '/Sessions/Playing/Progress',
      this.progressInfo(itemId, positionSeconds, isPaused, eventName)
    );
  }

  async reportStopped(itemId: string, positionSeconds: number): Promise<void> {
    if (!this.client.isAuthenticated()) {
      throw new Error('Not authenticated');
    }

    const info: PlaybackStopInfo = {
      ItemId: itemId,
      PositionTicks: secondsToTicks(positionSeconds),
      EventTime: Date.now(),
    };

    await this.client.post('/Sessions/Playing/Stopped', info);
  }

  // Best-effort durable write for page unload: a keepalive POST that outlives the tab so the resume
  // position is not lost when the listener closes/backgrounds instead of pausing. Synchronous,
  // never throws — paired with a localStorage write-through on the client for full coverage.
  flushProgress(itemId: string, positionSeconds: number, isPaused: boolean): void {
    if (!this.client.isAuthenticated()) return;
    this.client.sendKeepAlive(
      '/Sessions/Playing/Progress',
      this.progressInfo(itemId, positionSeconds, isPaused, 'Pause')
    );
  }

  private progressInfo(
    itemId: string,
    positionSeconds: number,
    isPaused: boolean,
    eventName?: PlaybackProgressInfo['EventName']
  ): PlaybackProgressInfo {
    return {
      ItemId: itemId,
      PositionTicks: secondsToTicks(positionSeconds),
      IsPaused: isPaused,
      PlayMethod: 'DirectPlay',
      VolumeLevel: 100,
      IsMuted: false,
      EventName: eventName,
      EventTime: Date.now(),
    };
  }
}
