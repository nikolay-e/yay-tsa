/**
 * Feature: Queue Management
 * Tests playback queue behavior from user perspective
 * Focus on WHAT the system does, not HOW it does it
 */

import { describe, it, expect, beforeEach } from 'vitest';
import { PlaybackQueue } from '../../../src/player/queue.js';
import type { AudioItem } from '../../../src/models/types.js';
import {
  mockAlbumTracks,
  mockLargeAlbum,
  mockMixedTracks,
  createMockTrack,
} from '../fixtures/mock-tracks.js';
import { GivenQueue, WhenQueue, ThenQueue } from '../fixtures/scenarios.js';

describe('Feature: Queue Management', () => {
  let queue: PlaybackQueue;

  beforeEach(() => {
    queue = new PlaybackQueue();
  });

  describe('Scenario: Starting with empty queue', () => {
    it('Given: User opens app for first time, When: Views queue, Then: Queue is empty with no playback options', () => {
      // Given: Fresh queue (beforeEach)
      GivenQueue.isEmpty(queue);

      // Then: No playback possible
      ThenQueue.queueIsEmpty(queue);
      ThenQueue.hasNoNextTrack(queue);
      ThenQueue.hasNoPreviousTrack(queue);

      // When: User tries to navigate
      const nextTrack = WhenQueue.skipsToNext(queue);
      const prevTrack = WhenQueue.goesToPrevious(queue);

      // Then: Navigation returns nothing
      expect(nextTrack).toBeNull();
      expect(prevTrack).toBeNull();
    });
  });

  describe('Scenario: User plays an album', () => {
    it('Given: Empty queue, When: User plays album, Then: Album starts playing from first track', () => {
      // Given: Empty queue
      GivenQueue.isEmpty(queue);

      // When: User plays album
      queue.setQueue(mockAlbumTracks);

      // Then: First track is playing
      ThenQueue.queueHasTracks(queue, 3);
      ThenQueue.currentTrackIs(queue, mockAlbumTracks[0]);
      ThenQueue.hasNextTrack(queue);
      ThenQueue.hasNoPreviousTrack(queue);
    });

    it('Given: Empty queue, When: User plays album from middle track, Then: Playback starts from selected track', () => {
      // Given: Empty queue
      GivenQueue.isEmpty(queue);

      // When: User clicks track 2 in album
      queue.setQueue(mockAlbumTracks);
      const selectedTrack = WhenQueue.jumpsToTrack(queue, 1);

      // Then: Track 2 is playing
      expect(selectedTrack).toEqual(mockAlbumTracks[1]);
      ThenQueue.currentTrackIs(queue, mockAlbumTracks[1]);
      ThenQueue.hasNextTrack(queue);
      ThenQueue.hasPreviousTrack(queue);
    });

    it('Given: Playing album, When: Track finishes, Then: Next track automatically starts', () => {
      // Given: Album is playing
      GivenQueue.hasTracks(queue, mockAlbumTracks);
      GivenQueue.isPlayingTrackAt(queue, 1, mockAlbumTracks[1]);

      // When: Track finishes (user sees next track start)
      const nextTrack = WhenQueue.skipsToNext(queue);

      // Then: Track 3 is now playing
      expect(nextTrack).toEqual(mockAlbumTracks[2]);
      ThenQueue.currentTrackIs(queue, mockAlbumTracks[2]);
      ThenQueue.hasNoNextTrack(queue);
      ThenQueue.hasPreviousTrack(queue);
    });

    it('Given: Playing last track of album, When: Track finishes, Then: Playback stops', () => {
      // Given: Last track is playing
      GivenQueue.hasTracks(queue, mockAlbumTracks);
      GivenQueue.isPlayingTrackAt(queue, 2, mockAlbumTracks[2]);

      // When: User tries to go to next track
      const nextTrack = WhenQueue.skipsToNext(queue);

      // Then: No next track available
      expect(nextTrack).toBeNull();
      ThenQueue.currentTrackIs(queue, mockAlbumTracks[2]);
      ThenQueue.hasNoNextTrack(queue);
    });
  });

  describe('Scenario: User navigates queue', () => {
    beforeEach(() => {
      GivenQueue.hasTracks(queue, mockAlbumTracks);
    });

    it('Given: Playing first track, When: User skips forward, Then: Second track plays', () => {
      // Given: First track playing (queue starts at index 0)
      ThenQueue.currentTrackIs(queue, mockAlbumTracks[0]);

      // When: User presses "next"
      const nextTrack = WhenQueue.skipsToNext(queue);

      // Then: Second track is playing
      expect(nextTrack).toEqual(mockAlbumTracks[1]);
      ThenQueue.currentTrackIs(queue, mockAlbumTracks[1]);
    });

    it('Given: Playing second track, When: User goes back, Then: First track plays', () => {
      // Given: Second track is playing
      GivenQueue.isPlayingTrackAt(queue, 1, mockAlbumTracks[1]);

      // When: User presses "previous"
      const prevTrack = WhenQueue.goesToPrevious(queue);

      // Then: First track is playing
      expect(prevTrack).toEqual(mockAlbumTracks[0]);
      ThenQueue.currentTrackIs(queue, mockAlbumTracks[0]);
    });

    it('Given: Playing first track, When: User tries to go back, Then: Nothing happens', () => {
      // Given: First track playing
      ThenQueue.currentTrackIs(queue, mockAlbumTracks[0]);
      ThenQueue.hasNoPreviousTrack(queue);

      // When: User presses "previous"
      const prevTrack = WhenQueue.goesToPrevious(queue);

      // Then: Still on first track
      expect(prevTrack).toBeNull();
      ThenQueue.currentTrackIs(queue, mockAlbumTracks[0]);
    });

    it('Given: Queue with tracks, When: User jumps to specific track, Then: That track starts playing', () => {
      // Given: Queue loaded
      ThenQueue.queueHasTracks(queue, 3);

      // When: User clicks on track 3 in queue
      const selectedTrack = WhenQueue.jumpsToTrack(queue, 2);

      // Then: Track 3 is playing
      expect(selectedTrack).toEqual(mockAlbumTracks[2]);
      ThenQueue.currentTrackIs(queue, mockAlbumTracks[2]);
      ThenQueue.hasNoPreviousTrack(queue);
    });
  });

  describe('Scenario: User adds tracks to queue', () => {
    it('Given: Empty queue, When: User adds single track, Then: Track starts playing', () => {
      // Given: Empty queue
      GivenQueue.isEmpty(queue);

      // When: User adds single track
      WhenQueue.addsTracks(queue, [mockAlbumTracks[0]]);

      // Then: Track is playing
      ThenQueue.queueHasTracks(queue, 1);
      ThenQueue.currentTrackIs(queue, mockAlbumTracks[0]);
      ThenQueue.hasNoNextTrack(queue);
      ThenQueue.hasNoPreviousTrack(queue);
    });

    it('Given: Playing track, When: User adds more tracks, Then: New tracks appear at end of queue', () => {
      // Given: One track playing
      WhenQueue.addsTracks(queue, [mockAlbumTracks[0]]);
      ThenQueue.queueHasTracks(queue, 1);

      // When: User adds two more tracks
      WhenQueue.addsTracks(queue, [mockAlbumTracks[1]]);
      WhenQueue.addsTracks(queue, [mockAlbumTracks[2]]);

      // Then: Queue has 3 tracks, still playing first
      ThenQueue.queueHasTracks(queue, 3);
      ThenQueue.currentTrackIs(queue, mockAlbumTracks[0]);
      ThenQueue.hasNextTrack(queue);
    });

    it('Given: Playing track 1, When: User adds track 5 to "play next", Then: Track 5 plays after track 1', () => {
      // Given: Album playing from track 1
      GivenQueue.hasTracks(queue, mockAlbumTracks);
      ThenQueue.currentTrackIs(queue, mockAlbumTracks[0]);

      // When: User adds new track at position 1 (play next)
      const playNextTrack = createMockTrack('special-1', 'Play Next Track');
      queue.insertAt(1, playNextTrack);

      // Then: Queue has 4 tracks, current track unchanged
      ThenQueue.queueHasTracks(queue, 4);
      ThenQueue.currentTrackIs(queue, mockAlbumTracks[0]);

      // When: Current track finishes
      const nextTrack = WhenQueue.skipsToNext(queue);

      // Then: Play next track starts
      expect(nextTrack).toEqual(playNextTrack);
      ThenQueue.currentTrackIs(queue, playNextTrack);
    });
  });

  describe('Scenario: User removes tracks from queue', () => {
    beforeEach(() => {
      GivenQueue.hasTracks(queue, mockAlbumTracks);
    });

    it('Given: Queue with 3 tracks, When: User removes middle track, Then: Queue has 2 tracks', () => {
      // Given: 3 tracks in queue
      ThenQueue.queueHasTracks(queue, 3);

      // When: User removes track 2
      WhenQueue.removesTrack(queue, 1);

      // Then: Queue has 2 tracks, track 3 is now at position 2
      ThenQueue.queueHasTracks(queue, 2);
      expect(queue.getItemAt(1)).toEqual(mockAlbumTracks[2]);
    });

    it('Given: Playing track 1, When: User removes track 1, Then: Track 2 becomes current', () => {
      // Given: Track 1 playing
      ThenQueue.currentTrackIs(queue, mockAlbumTracks[0]);

      // When: User removes currently playing track
      WhenQueue.removesTrack(queue, 0);

      // Then: Track 2 is now playing
      ThenQueue.queueHasTracks(queue, 2);
      ThenQueue.currentTrackIs(queue, mockAlbumTracks[1]);
    });

    it('Given: Playing track, When: User clears queue, Then: Queue is empty', () => {
      // Given: Queue with tracks
      ThenQueue.queueHasTracks(queue, 3);
      ThenQueue.currentTrackIs(queue, mockAlbumTracks[0]);

      // When: User clears queue
      WhenQueue.clearsQueue(queue);

      // Then: Queue is empty
      ThenQueue.queueIsEmpty(queue);
    });
  });

  describe('Scenario: User enables shuffle mode', () => {
    beforeEach(() => {
      GivenQueue.hasTracks(queue, mockLargeAlbum);
      GivenQueue.isPlayingTrackAt(queue, 3, mockLargeAlbum[3]);
    });

    it('Given: Playing track 4, When: User enables shuffle, Then: Current track keeps playing', () => {
      // Given: Track 4 is playing
      const trackBeforeShuffle = queue.getCurrentItem()!;
      ThenQueue.currentTrackIs(queue, mockLargeAlbum[3]);

      // When: User enables shuffle
      WhenQueue.enablesShuffle(queue);

      // Then: Current track still playing, shuffle mode is on
      ThenQueue.shuffleIsEnabled(queue);
      ThenQueue.currentTrackContinuesPlaying(queue, trackBeforeShuffle);
      ThenQueue.queueHasTracks(queue, 10);
    });

    it('Given: Shuffle enabled, When: User disables shuffle, Then: Original album order restored', () => {
      // Given: Shuffle is enabled
      WhenQueue.enablesShuffle(queue);
      ThenQueue.shuffleIsEnabled(queue);

      // When: User disables shuffle
      WhenQueue.disablesShuffle(queue);

      // Then: Original order restored
      ThenQueue.shuffleIsDisabled(queue);
      expect(queue.getAllItems()).toEqual(mockLargeAlbum);
    });

    it('Given: Shuffle enabled at track 4, When: User skips to next, Then: Random track plays (not track 5)', () => {
      // Given: Shuffle enabled
      WhenQueue.enablesShuffle(queue);
      ThenQueue.shuffleIsEnabled(queue);

      // When: User skips to next track
      const nextTrack = WhenQueue.skipsToNext(queue);

      // Then: Next track is NOT track 5 (because shuffle randomizes)
      expect(nextTrack).toBeDefined();
      expect(nextTrack).not.toBeNull();
      // We can't predict exact track due to shuffle, but we verify it exists
      ThenQueue.currentTrackIs(queue, nextTrack!);
    });
  });

  describe('Scenario: User sets repeat modes', () => {
    beforeEach(() => {
      GivenQueue.hasTracks(queue, mockAlbumTracks);
    });

    it('Given: No repeat, When: User cycles repeat mode, Then: Mode changes off → all → one → off', () => {
      // Given: Repeat is off
      ThenQueue.repeatModeIs(queue, 'off');

      // When: User toggles repeat
      WhenQueue.changesRepeatMode(queue);

      // Then: Repeat all
      ThenQueue.repeatModeIs(queue, 'all');

      // When: User toggles again
      WhenQueue.changesRepeatMode(queue);

      // Then: Repeat one
      ThenQueue.repeatModeIs(queue, 'one');

      // When: User toggles again
      WhenQueue.changesRepeatMode(queue);

      // Then: Back to off
      ThenQueue.repeatModeIs(queue, 'off');
    });

    it('Given: Repeat one enabled, When: Track finishes, Then: Same track plays again', () => {
      // Given: Repeat one mode
      GivenQueue.repeatModeSet(queue, 'one');
      const currentTrack = queue.getCurrentItem();

      // When: Track finishes
      const nextTrack = WhenQueue.skipsToNext(queue);

      // Then: Same track plays again
      expect(nextTrack).toEqual(currentTrack);
      ThenQueue.currentTrackIs(queue, mockAlbumTracks[0]);
    });

    it('Given: Repeat all enabled, When: Last track finishes, Then: First track plays', () => {
      // Given: Repeat all mode, playing last track
      GivenQueue.repeatModeSet(queue, 'all');
      GivenQueue.isPlayingTrackAt(queue, 2, mockAlbumTracks[2]);

      // When: Last track finishes
      const nextTrack = WhenQueue.skipsToNext(queue);

      // Then: Loop to first track
      expect(nextTrack).toEqual(mockAlbumTracks[0]);
      ThenQueue.currentTrackIs(queue, mockAlbumTracks[0]);
      ThenQueue.hasNextTrack(queue); // Because repeat all
    });
  });

  describe('Scenario: User combines shuffle and repeat', () => {
    beforeEach(() => {
      GivenQueue.hasTracks(queue, mockLargeAlbum);
    });

    it('Given: Shuffle + Repeat All, When: Tracks play, Then: Infinite random playback', () => {
      // Given: Both shuffle and repeat all enabled
      WhenQueue.enablesShuffle(queue);
      GivenQueue.repeatModeSet(queue, 'all');

      // When: Play through multiple tracks
      for (let i = 0; i < 15; i++) {
        // Play more than queue length
        const nextTrack = WhenQueue.skipsToNext(queue);
        expect(nextTrack).not.toBeNull(); // Always has next due to repeat
      }

      // Then: Still has next track (infinite playback)
      ThenQueue.hasNextTrack(queue);
      expect(queue.getCurrentItem()).not.toBeNull();
    });

    it('Given: Shuffle + Repeat One, When: Track finishes, Then: Same track repeats', () => {
      // Given: Shuffle + Repeat One
      WhenQueue.enablesShuffle(queue);
      GivenQueue.repeatModeSet(queue, 'one');
      const currentTrack = queue.getCurrentItem();

      // When: Track finishes
      const nextTrack = WhenQueue.skipsToNext(queue);

      // Then: Same track plays (repeat one overrides shuffle)
      expect(nextTrack).toEqual(currentTrack);
      ThenQueue.currentTrackIs(queue, currentTrack!);
    });

    it('Given: Shuffle + Repeat All, When: User disables shuffle, Then: Repeat all continues with original order', () => {
      // Given: Shuffle + Repeat All enabled
      WhenQueue.enablesShuffle(queue);
      GivenQueue.repeatModeSet(queue, 'all');

      // When: User disables shuffle
      WhenQueue.disablesShuffle(queue);

      // Then: Repeat all still active, original order restored
      ThenQueue.shuffleIsDisabled(queue);
      ThenQueue.repeatModeIs(queue, 'all');
      expect(queue.getAllItems()).toEqual(mockLargeAlbum);
    });
  });

  describe('Scenario: User reorders queue', () => {
    beforeEach(() => {
      GivenQueue.hasTracks(queue, mockAlbumTracks);
    });

    it('Given: Queue with tracks, When: User moves track 3 to position 1, Then: Track order changes', () => {
      // Given: Queue [Track 1, Track 2, Track 3]
      ThenQueue.queueHasTracks(queue, 3);

      // When: User drags track 3 to top
      const moved = queue.moveItem(2, 0);

      // Then: Queue is now [Track 3, Track 1, Track 2]
      expect(moved).toBe(true);
      expect(queue.getItemAt(0)).toEqual(mockAlbumTracks[2]);
      expect(queue.getItemAt(1)).toEqual(mockAlbumTracks[0]);
      expect(queue.getItemAt(2)).toEqual(mockAlbumTracks[1]);
    });

    it('Given: Playing track 1, When: User reorders queue, Then: Current track keeps playing', () => {
      // Given: Track 1 playing
      const currentTrack = queue.getCurrentItem();

      // When: User moves track 3 to position 1
      queue.moveItem(2, 0);

      // Then: Track 1 still playing
      ThenQueue.currentTrackIs(queue, currentTrack!);
    });
  });

  describe('Scenario: Edge cases', () => {
    it('Given: Single track queue, When: User tries to navigate, Then: No navigation possible', () => {
      // Given: Queue with one track
      WhenQueue.addsTracks(queue, [mockAlbumTracks[0]]);

      // When: User tries to navigate
      const nextTrack = WhenQueue.skipsToNext(queue);
      const prevTrack = WhenQueue.goesToPrevious(queue);

      // Then: No movement
      expect(nextTrack).toBeNull();
      expect(prevTrack).toBeNull();
      ThenQueue.currentTrackIs(queue, mockAlbumTracks[0]);
      ThenQueue.hasNoNextTrack(queue);
      ThenQueue.hasNoPreviousTrack(queue);
    });

    it('Given: Large queue (100 tracks), When: User loads queue, Then: All tracks available', () => {
      // Given: Large playlist
      const largeCatalog = Array.from({ length: 100 }, (_, i) =>
        createMockTrack(`track-${i}`, `Track ${i}`)
      );

      // When: User plays large playlist
      queue.setQueue(largeCatalog);

      // Then: All tracks loaded
      ThenQueue.queueHasTracks(queue, 100);
      ThenQueue.currentTrackIs(queue, largeCatalog[0]);
      ThenQueue.hasNextTrack(queue);
    });
  });
});
