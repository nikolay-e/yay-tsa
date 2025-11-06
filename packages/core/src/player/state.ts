/**
 * Playback State Management
 * Manages player state and playback reporting to Jellyfin server
 */

import { JellyfinClient } from '../api/client.js';
import {
  PlayerState,
  PlaybackStatus,
  AudioItem,
  RepeatMode,
  ShuffleMode,
  PlaybackProgressInfo,
  PlaybackStartInfo,
  PlaybackStopInfo,
} from '../models/types.js';
import { secondsToTicks, ticksToSeconds } from '../config/constants.js';

/**
 * Playback Reporter
 * Simplified class for reporting playback events to Jellyfin server
 * Useful for testing and standalone reporting scenarios
 */
export class PlaybackReporter {
  constructor(private client: JellyfinClient) {}

  /**
   * Report playback start
   */
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

  /**
   * Report playback progress
   */
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

  /**
   * Report playback stopped
   */
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

export class PlaybackState {
  private state: PlayerState = {
    status: 'idle',
    currentItem: null,
    currentTime: 0,
    duration: 0,
    volume: 0.7,
    muted: false,
    repeatMode: 'off',
    shuffleMode: 'off',
    error: null,
  };

  private progressReportTimer: ReturnType<typeof setTimeout> | null = null;
  private readonly PROGRESS_REPORT_INTERVAL = 10000; // 10 seconds

  constructor(private client: JellyfinClient) {}

  /**
   * Get current state
   */
  getState(): Readonly<PlayerState> {
    return { ...this.state };
  }

  /**
   * Set playback status
   */
  setStatus(status: PlaybackStatus): void {
    this.state.status = status;
    this.state.error = null;
  }

  /**
   * Set error state
   */
  setError(error: Error): void {
    this.state.status = 'error';
    this.state.error = error;
  }

  /**
   * Clear error
   */
  clearError(): void {
    if (this.state.status === 'error') {
      this.state.status = 'idle';
    }
    this.state.error = null;
  }

  /**
   * Set current item
   */
  setCurrentItem(item: AudioItem | null): void {
    this.state.currentItem = item;
    this.state.currentTime = 0;

    if (item?.RunTimeTicks) {
      this.state.duration = ticksToSeconds(item.RunTimeTicks);
    } else {
      this.state.duration = 0;
    }
  }

  /**
   * Update current playback time
   */
  setCurrentTime(seconds: number): void {
    this.state.currentTime = seconds;
  }

  /**
   * Update duration
   */
  setDuration(seconds: number): void {
    this.state.duration = seconds;
  }

  /**
   * Set volume (0-1)
   */
  setVolume(volume: number): void {
    if (!Number.isFinite(volume)) {
      console.warn('Invalid volume value (NaN/Infinity), defaulting to 0.7');
      this.state.volume = 0.7;
      return;
    }
    this.state.volume = Math.max(0, Math.min(1, volume));
  }

  /**
   * Set muted state
   */
  setMuted(muted: boolean): void {
    this.state.muted = muted;
  }

  /**
   * Set repeat mode
   */
  setRepeatMode(mode: RepeatMode): void {
    this.state.repeatMode = mode;
  }

  /**
   * Set shuffle mode
   */
  setShuffleMode(mode: ShuffleMode): void {
    this.state.shuffleMode = mode;
  }

  /**
   * Get current item
   */
  getCurrentItem(): AudioItem | null {
    return this.state.currentItem;
  }

  /**
   * Get current time in seconds
   */
  getCurrentTime(): number {
    return this.state.currentTime;
  }

  /**
   * Get duration in seconds
   */
  getDuration(): number {
    return this.state.duration;
  }

  /**
   * Get current time in ticks (Jellyfin format)
   */
  getCurrentTimeTicks(): number {
    return secondsToTicks(this.state.currentTime);
  }

  /**
   * Report playback start to Jellyfin server
   */
  async reportPlaybackStart(): Promise<void> {
    if (!this.state.currentItem) return;
    if (!this.client.isAuthenticated()) return;

    const info: PlaybackStartInfo = {
      ItemId: this.state.currentItem.Id,
      PositionTicks: this.getCurrentTimeTicks(),
      IsPaused: this.state.status === 'paused',
      PlayMethod: 'DirectPlay',
      CanSeek: true,
      VolumeLevel: Math.round(this.state.volume * 100),
      IsMuted: this.state.muted,
    };

    try {
      await this.client.post('/Sessions/Playing', info);
      this.startProgressReporting();
    } catch (error) {
      console.error('Failed to report playback start:', error);
    }
  }

  /**
   * Report playback progress to Jellyfin server
   */
  async reportPlaybackProgress(): Promise<void> {
    if (!this.state.currentItem) return;
    if (!this.client.isAuthenticated()) return;

    const info: PlaybackProgressInfo = {
      ItemId: this.state.currentItem.Id,
      PositionTicks: this.getCurrentTimeTicks(),
      IsPaused: this.state.status === 'paused',
      CanSeek: true, // Audio playback always supports seeking
      PlayMethod: 'DirectPlay',
      VolumeLevel: Math.round(this.state.volume * 100),
      IsMuted: this.state.muted,
    };

    try {
      await this.client.post('/Sessions/Playing/Progress', info);
    } catch (error) {
      console.error('Failed to report playback progress:', error);
    }
  }

  /**
   * Report playback stop to Jellyfin server
   */
  async reportPlaybackStop(): Promise<void> {
    this.stopProgressReporting();

    if (!this.state.currentItem) return;
    if (!this.client.isAuthenticated()) return;

    const info: PlaybackStopInfo = {
      ItemId: this.state.currentItem.Id,
      PositionTicks: this.getCurrentTimeTicks(),
    };

    try {
      await this.client.post('/Sessions/Playing/Stopped', info);
    } catch (error) {
      console.error('Failed to report playback stop:', error);
    }
  }

  /**
   * Start automatic progress reporting
   */
  private startProgressReporting(): void {
    this.stopProgressReporting();

    this.progressReportTimer = setInterval(() => {
      if (this.state.status === 'playing' || this.state.status === 'paused') {
        void this.reportPlaybackProgress();
      }
    }, this.PROGRESS_REPORT_INTERVAL);
  }

  /**
   * Stop automatic progress reporting
   */
  private stopProgressReporting(): void {
    if (this.progressReportTimer) {
      clearInterval(this.progressReportTimer);
      this.progressReportTimer = null;
    }
  }

  /**
   * Cleanup resources
   */
  dispose(): void {
    this.stopProgressReporting();
  }

  /**
   * Reset state to idle
   */
  reset(): void {
    this.stopProgressReporting();
    this.state = {
      status: 'idle',
      currentItem: null,
      currentTime: 0,
      duration: 0,
      volume: this.state.volume, // Preserve volume
      muted: this.state.muted, // Preserve mute
      repeatMode: this.state.repeatMode,
      shuffleMode: this.state.shuffleMode,
      error: null,
    };
  }
}
