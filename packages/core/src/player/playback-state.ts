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
    eventName?: PlaybackProgressInfo['EventName'],
    eventTimeMs?: number
  ): Promise<void> {
    if (!this.client.isAuthenticated()) {
      throw new Error('Not authenticated');
    }

    await this.client.post(
      '/Sessions/Playing/Progress',
      this.progressInfo(itemId, positionSeconds, isPaused, eventName, eventTimeMs)
    );
  }

  async reportStopped(
    itemId: string,
    positionSeconds: number,
    eventTimeMs?: number
  ): Promise<void> {
    if (!this.client.isAuthenticated()) {
      throw new Error('Not authenticated');
    }

    const info: PlaybackStopInfo = {
      ItemId: itemId,
      PositionTicks: secondsToTicks(positionSeconds),
      EventTime: eventTimeMs ?? Date.now(),
    };

    await this.client.post('/Sessions/Playing/Stopped', info);
  }

  // Best-effort durable write for page unload: a keepalive POST that outlives the tab so the resume
  // position is not lost when the listener closes/backgrounds instead of pausing. Synchronous,
  // never throws — paired with a localStorage write-through on the client for full coverage.
  // eventTimeMs should be the instant the position was last live (engine tick/seek), not the flush
  // instant: a long-stale tab flushing on teardown must lose the server merge against newer progress.
  flushProgress(
    itemId: string,
    positionSeconds: number,
    isPaused: boolean,
    eventTimeMs?: number
  ): void {
    if (!this.client.isAuthenticated()) return;
    this.client.sendKeepAlive(
      '/Sessions/Playing/Progress',
      this.progressInfo(itemId, positionSeconds, isPaused, undefined, eventTimeMs)
    );
  }

  private progressInfo(
    itemId: string,
    positionSeconds: number,
    isPaused: boolean,
    eventName?: PlaybackProgressInfo['EventName'],
    eventTimeMs?: number
  ): PlaybackProgressInfo {
    return {
      ItemId: itemId,
      PositionTicks: secondsToTicks(positionSeconds),
      IsPaused: isPaused,
      PlayMethod: 'DirectPlay',
      VolumeLevel: 100,
      IsMuted: false,
      EventName: eventName,
      EventTime: eventTimeMs ?? Date.now(),
    };
  }
}
