import { describe, it, expect, beforeEach, vi } from 'vitest';
import { PlaybackState } from '../src/player/state.js';
import { JellyfinClient } from '../src/api/client.js';
import { AudioItem } from '../src/models/types.js';

describe('PlaybackState', () => {
  let state: PlaybackState;
  let mockClient: JellyfinClient;
  let testItem: AudioItem;

  beforeEach(() => {
    mockClient = {
      isAuthenticated: vi.fn(() => true),
      post: vi.fn(() => Promise.resolve())
    } as any;

    state = new PlaybackState(mockClient);

    testItem = {
      Type: 'Audio',
      Name: 'Test Track',
      ServerId: 'test-server',
      Id: 'test-id',
      RunTimeTicks: 1_800_000_000 // 3 minutes = 180 seconds (10M ticks/sec)
    };
  });

  describe('Initial state', () => {
    it('should start in idle state', () => {
      const currentState = state.getState();

      expect(currentState.status).toBe('idle');
      expect(currentState.currentItem).toBeNull();
      expect(currentState.currentTime).toBe(0);
      expect(currentState.duration).toBe(0);
      expect(currentState.volume).toBe(0.7);
      expect(currentState.muted).toBe(false);
      expect(currentState.error).toBeNull();
    });
  });

  describe('Status management', () => {
    it('should set status', () => {
      state.setStatus('playing');

      expect(state.getState().status).toBe('playing');
    });

    it('should set error state', () => {
      const error = new Error('Test error');

      state.setError(error);

      const currentState = state.getState();
      expect(currentState.status).toBe('error');
      expect(currentState.error).toBe(error);
    });

    it('should clear error', () => {
      state.setError(new Error('Test error'));
      state.clearError();

      const currentState = state.getState();
      expect(currentState.status).toBe('idle');
      expect(currentState.error).toBeNull();
    });
  });

  describe('Item management', () => {
    it('should set current item', () => {
      state.setCurrentItem(testItem);

      expect(state.getCurrentItem()).toBe(testItem);
      expect(state.getDuration()).toBe(180); // 3 minutes
    });

    it('should reset time when setting new item', () => {
      state.setCurrentTime(30);
      state.setCurrentItem(testItem);

      expect(state.getCurrentTime()).toBe(0);
    });

    it('should handle item without RunTimeTicks', () => {
      const itemNoTicks = { ...testItem, RunTimeTicks: undefined };

      state.setCurrentItem(itemNoTicks);

      expect(state.getDuration()).toBe(0);
    });
  });

  describe('Time management', () => {
    beforeEach(() => {
      state.setCurrentItem(testItem);
    });

    it('should set current time', () => {
      state.setCurrentTime(30);

      expect(state.getCurrentTime()).toBe(30);
    });

    it('should set duration', () => {
      state.setDuration(200);

      expect(state.getDuration()).toBe(200);
    });

    it('should get current time in ticks', () => {
      state.setCurrentTime(30);

      expect(state.getCurrentTimeTicks()).toBe(300_000_000);
    });

    it('should convert seconds to ticks', () => {
      expect(PlaybackState.secondsToTicks(30)).toBe(300_000_000);
      expect(PlaybackState.secondsToTicks(0)).toBe(0);
      expect(PlaybackState.secondsToTicks(0.1)).toBe(1_000_000);
    });

    it('should convert ticks to seconds', () => {
      expect(PlaybackState.ticksToSeconds(300_000_000)).toBe(30);
      expect(PlaybackState.ticksToSeconds(0)).toBe(0);
      expect(PlaybackState.ticksToSeconds(1_000_000)).toBe(0.1);
    });
  });

  describe('Volume and mute', () => {
    it('should set volume', () => {
      state.setVolume(0.5);

      expect(state.getState().volume).toBe(0.5);
    });

    it('should clamp volume to 0-1 range', () => {
      state.setVolume(1.5);
      expect(state.getState().volume).toBe(1);

      state.setVolume(-0.5);
      expect(state.getState().volume).toBe(0);
    });

    it('should set muted state', () => {
      state.setMuted(true);

      expect(state.getState().muted).toBe(true);
    });
  });

  describe('Repeat and shuffle modes', () => {
    it('should set repeat mode', () => {
      state.setRepeatMode('all');

      expect(state.getState().repeatMode).toBe('all');
    });

    it('should set shuffle mode', () => {
      state.setShuffleMode('on');

      expect(state.getState().shuffleMode).toBe('on');
    });
  });

  describe('Playback reporting', () => {
    beforeEach(() => {
      state.setCurrentItem(testItem);
      state.setCurrentTime(30);
    });

    it('should report playback start', async () => {
      await state.reportPlaybackStart();

      expect(mockClient.post).toHaveBeenCalledWith(
        '/Sessions/Playing',
        expect.objectContaining({
          ItemId: 'test-id',
          PositionTicks: 300_000_000,
          IsPaused: false,
          PlayMethod: 'DirectPlay',
          CanSeek: true
        })
      );
    });

    it('should report playback progress', async () => {
      await state.reportPlaybackProgress();

      expect(mockClient.post).toHaveBeenCalledWith(
        '/Sessions/Playing/Progress',
        expect.objectContaining({
          ItemId: 'test-id',
          PositionTicks: 300_000_000,
          IsPaused: false
        })
      );
    });

    it('should report paused state', async () => {
      state.setStatus('paused');

      await state.reportPlaybackProgress();

      expect(mockClient.post).toHaveBeenCalledWith(
        '/Sessions/Playing/Progress',
        expect.objectContaining({
          IsPaused: true
        })
      );
    });

    it('should report playback stop', async () => {
      await state.reportPlaybackStop();

      expect(mockClient.post).toHaveBeenCalledWith(
        '/Sessions/Playing/Stopped',
        expect.objectContaining({
          ItemId: 'test-id',
          PositionTicks: 300_000_000
        })
      );
    });

    it('should not report when no current item', async () => {
      state.setCurrentItem(null);

      await state.reportPlaybackStart();
      await state.reportPlaybackProgress();
      await state.reportPlaybackStop();

      expect(mockClient.post).not.toHaveBeenCalled();
    });

    it('should not report when not authenticated', async () => {
      (mockClient.isAuthenticated as any).mockReturnValue(false);

      await state.reportPlaybackStart();

      expect(mockClient.post).not.toHaveBeenCalled();
    });

    it('should handle reporting errors gracefully', async () => {
      (mockClient.post as any).mockRejectedValue(new Error('Network error'));

      // Should not throw
      await expect(state.reportPlaybackStart()).resolves.toBeUndefined();
      await expect(state.reportPlaybackProgress()).resolves.toBeUndefined();
      await expect(state.reportPlaybackStop()).resolves.toBeUndefined();
    });
  });

  describe('Reset and cleanup', () => {
    beforeEach(() => {
      state.setCurrentItem(testItem);
      state.setStatus('playing');
      state.setVolume(0.5);
      state.setMuted(true);
      state.setRepeatMode('all');
    });

    it('should reset state', () => {
      state.reset();

      const currentState = state.getState();
      expect(currentState.status).toBe('idle');
      expect(currentState.currentItem).toBeNull();
      expect(currentState.currentTime).toBe(0);
      expect(currentState.duration).toBe(0);
      // Preserve these
      expect(currentState.volume).toBe(0.5);
      expect(currentState.muted).toBe(true);
      expect(currentState.repeatMode).toBe('all');
    });

    it('should dispose resources', () => {
      state.dispose();

      // Should complete without errors
      expect(true).toBe(true);
    });
  });

  describe('State immutability', () => {
    it('should return copy of state', () => {
      const state1 = state.getState();
      const state2 = state.getState();

      expect(state1).not.toBe(state2);
      expect(state1).toEqual(state2);
    });

    it('should not allow external mutation', () => {
      const stateSnapshot = state.getState();

      stateSnapshot.volume = 0.9;

      expect(state.getState().volume).toBe(0.7);
    });
  });
});
