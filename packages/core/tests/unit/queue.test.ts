/**
 * Unit Queue Tests
 * Tests playback queue operations with mock data (no server interaction)
 */

import { describe, it, expect, beforeEach } from 'vitest';
import { PlaybackQueue } from '../../src/player/queue.js';
import type { AudioItem } from '../../src/models/types.js';

describe('Unit: Playback Queue', () => {
  let queue: PlaybackQueue;

  // Mock track data that mimics real Media Server response
  const mockTracks: AudioItem[] = [
    {
      Id: 'track-1',
      Name: 'Track 1',
      Type: 'Audio',
      RunTimeTicks: 2400000000,
      AlbumId: 'album-1',
      Album: 'Test Album',
      Artists: ['Test Artist'],
      IndexNumber: 1,
    },
    {
      Id: 'track-2',
      Name: 'Track 2',
      Type: 'Audio',
      RunTimeTicks: 2500000000,
      AlbumId: 'album-1',
      Album: 'Test Album',
      Artists: ['Test Artist'],
      IndexNumber: 2,
    },
    {
      Id: 'track-3',
      Name: 'Track 3',
      Type: 'Audio',
      RunTimeTicks: 2600000000,
      AlbumId: 'album-1',
      Album: 'Test Album',
      Artists: ['Test Artist'],
      IndexNumber: 3,
    },
  ];

  beforeEach(() => {
    queue = new PlaybackQueue();
  });

  describe('Queue Operations', () => {
    it('should initialize with empty queue', () => {
      expect(queue.getAllItems()).toHaveLength(0);
      expect(queue.getCurrentItem()).toBeNull();
      expect(queue.getCurrentIndex()).toBe(-1);
      expect(queue.isEmpty()).toBe(true);
    });

    it('should set queue with tracks', () => {
      queue.setQueue(mockTracks);

      expect(queue.getAllItems()).toHaveLength(3);
      expect(queue.getCurrentItem()).toEqual(mockTracks[0]);
      expect(queue.getCurrentIndex()).toBe(0);
      expect(queue.isEmpty()).toBe(false);
    });

    it('should add single track to queue', () => {
      queue.addToQueue(mockTracks[0]);

      expect(queue.getAllItems()).toHaveLength(1);
      expect(queue.getCurrentItem()).toEqual(mockTracks[0]);
    });

    it('should add multiple tracks to queue', () => {
      queue.addToQueue(mockTracks[0]);
      queue.addToQueue(mockTracks[1]);
      queue.addToQueue(mockTracks[2]);

      expect(queue.getAllItems()).toHaveLength(3);
      expect(queue.getCurrentItem()).toEqual(mockTracks[0]);
    });

    it('should remove track from queue', () => {
      queue.setQueue(mockTracks);
      queue.removeAt(1);

      expect(queue.getAllItems()).toHaveLength(2);
      expect(queue.getAllItems()[1]).toEqual(mockTracks[2]);
    });

    it('should clear queue', () => {
      queue.setQueue(mockTracks);
      queue.clear();

      expect(queue.getAllItems()).toHaveLength(0);
      expect(queue.getCurrentItem()).toBeNull();
      expect(queue.isEmpty()).toBe(true);
    });
  });

  describe('Navigation', () => {
    beforeEach(() => {
      queue.setQueue(mockTracks);
    });

    it('should move to next track', () => {
      const nextTrack = queue.next();

      expect(nextTrack).toEqual(mockTracks[1]);
      expect(queue.getCurrentIndex()).toBe(1);
      expect(queue.getCurrentItem()).toEqual(mockTracks[1]);
    });

    it('should move to previous track', () => {
      queue.next();
      const prevTrack = queue.previous();

      expect(prevTrack).toEqual(mockTracks[0]);
      expect(queue.getCurrentIndex()).toBe(0);
      expect(queue.getCurrentItem()).toEqual(mockTracks[0]);
    });

    it('should return null when no next track', () => {
      queue.next();
      queue.next();
      const nextTrack = queue.next();

      expect(nextTrack).toBeNull();
      expect(queue.getCurrentIndex()).toBe(2);
    });

    it('should return null when no previous track', () => {
      const prevTrack = queue.previous();

      expect(prevTrack).toBeNull();
      expect(queue.getCurrentIndex()).toBe(0);
    });

    it('should jump to specific index', () => {
      const track = queue.jumpTo(2);

      expect(track).toEqual(mockTracks[2]);
      expect(queue.getCurrentIndex()).toBe(2);
      expect(queue.getCurrentItem()).toEqual(mockTracks[2]);
    });

    it('should check hasNext and hasPrevious', () => {
      expect(queue.hasNext()).toBe(true);
      expect(queue.hasPrevious()).toBe(false);

      queue.next();
      expect(queue.hasNext()).toBe(true);
      expect(queue.hasPrevious()).toBe(true);

      queue.next();
      expect(queue.hasNext()).toBe(false);
      expect(queue.hasPrevious()).toBe(true);
    });
  });

  describe('Shuffle', () => {
    beforeEach(() => {
      queue.setQueue(mockTracks);
    });

    it('should shuffle queue', () => {
      expect(queue.getShuffleMode()).toBe('off');

      queue.toggleShuffleMode();

      const shuffledQueue = queue.getAllItems();
      expect(shuffledQueue).toHaveLength(3);
      expect(shuffledQueue[0]).toEqual(mockTracks[0]); // Current track stays first
      expect(queue.getShuffleMode()).toBe('on');
    });

    it('should restore original order when unshuffle', () => {
      queue.toggleShuffleMode();
      queue.toggleShuffleMode();

      expect(queue.getAllItems()).toEqual(mockTracks);
      expect(queue.getShuffleMode()).toBe('off');
    });
  });

  describe('Repeat Modes', () => {
    beforeEach(() => {
      queue.setQueue(mockTracks);
    });

    it('should cycle through repeat modes', () => {
      expect(queue.getRepeatMode()).toBe('off');

      queue.toggleRepeatMode();
      expect(queue.getRepeatMode()).toBe('all');

      queue.toggleRepeatMode();
      expect(queue.getRepeatMode()).toBe('one');

      queue.toggleRepeatMode();
      expect(queue.getRepeatMode()).toBe('off');
    });

    it('should repeat current track in repeat-one mode', () => {
      queue.setRepeatMode('one');
      const currentTrack = queue.getCurrentItem();
      const nextTrack = queue.next();

      // Should still be on first track
      expect(nextTrack).toEqual(currentTrack);
      expect(queue.getCurrentIndex()).toBe(0);
      expect(queue.getCurrentItem()).toEqual(mockTracks[0]);
    });

    it('should loop to beginning in repeat-all mode', () => {
      queue.setRepeatMode('all');
      queue.jumpTo(2); // Last track
      const nextTrack = queue.next();

      expect(nextTrack).toEqual(mockTracks[0]);
      expect(queue.getCurrentIndex()).toBe(0);
      expect(queue.getCurrentItem()).toEqual(mockTracks[0]);
    });
  });

  describe('Queue Management', () => {
    it('should handle empty queue gracefully', () => {
      expect(queue.next()).toBeNull();
      expect(queue.previous()).toBeNull();
      expect(queue.getCurrentItem()).toBeNull();
      expect(queue.hasNext()).toBe(false);
      expect(queue.hasPrevious()).toBe(false);
    });

    it('should handle single track queue', () => {
      queue.addToQueue(mockTracks[0]);

      expect(queue.next()).toBeNull();
      expect(queue.previous()).toBeNull();
      expect(queue.getCurrentItem()).toEqual(mockTracks[0]);
      expect(queue.hasNext()).toBe(false);
      expect(queue.hasPrevious()).toBe(false);
    });

    it('should update current index when removing current track', () => {
      queue.setQueue(mockTracks);
      queue.removeAt(0);

      expect(queue.getCurrentIndex()).toBe(0);
      expect(queue.getCurrentItem()).toEqual(mockTracks[1]);
    });

    it('should get item at specific index', () => {
      queue.setQueue(mockTracks);

      expect(queue.getItemAt(0)).toEqual(mockTracks[0]);
      expect(queue.getItemAt(1)).toEqual(mockTracks[1]);
      expect(queue.getItemAt(2)).toEqual(mockTracks[2]);
      expect(queue.getItemAt(3)).toBeNull();
    });

    it('should move items in queue', () => {
      queue.setQueue(mockTracks);
      const moved = queue.moveItem(2, 0);

      expect(moved).toBe(true);
      expect(queue.getItemAt(0)).toEqual(mockTracks[2]);
      expect(queue.getItemAt(1)).toEqual(mockTracks[0]);
      expect(queue.getItemAt(2)).toEqual(mockTracks[1]);
    });
  });

  describe('Continuous Album Playback', () => {
    beforeEach(() => {
      queue.setQueue(mockTracks);
    });

    it('should start playback from specific index and continue through album', () => {
      // Start from track 2 (index 1)
      const track = queue.jumpTo(1);

      expect(track).toEqual(mockTracks[1]);
      expect(queue.getCurrentIndex()).toBe(1);
      expect(queue.hasNext()).toBe(true);

      // Verify next track is track 3
      const nextTrack = queue.next();
      expect(nextTrack).toEqual(mockTracks[2]);
      expect(queue.getCurrentIndex()).toBe(2);

      // Verify no more tracks after the last one
      expect(queue.hasNext()).toBe(false);
      const finalNext = queue.next();
      expect(finalNext).toBeNull();
    });

    it('should set queue with starting index', () => {
      // Create a new queue with more tracks for better testing
      const albumTracks: AudioItem[] = [
        ...mockTracks,
        {
          Id: 'track-4',
          Name: 'Track 4',
          Type: 'Audio',
          RunTimeTicks: 2700000000,
          AlbumId: 'album-1',
          Album: 'Test Album',
          Artists: ['Test Artist'],
          IndexNumber: 4,
        },
        {
          Id: 'track-5',
          Name: 'Track 5',
          Type: 'Audio',
          RunTimeTicks: 2800000000,
          AlbumId: 'album-1',
          Album: 'Test Album',
          Artists: ['Test Artist'],
          IndexNumber: 5,
        },
      ];

      // Set queue and jump to track 3 (index 2)
      queue.setQueue(albumTracks);
      queue.jumpTo(2);

      expect(queue.getCurrentItem()).toEqual(albumTracks[2]);
      expect(queue.getCurrentIndex()).toBe(2);
      expect(queue.getAllItems()).toHaveLength(5);

      // Verify we can navigate through remaining tracks
      expect(queue.hasNext()).toBe(true);
      queue.next();
      expect(queue.getCurrentItem()).toEqual(albumTracks[3]);

      queue.next();
      expect(queue.getCurrentItem()).toEqual(albumTracks[4]);
      expect(queue.hasNext()).toBe(false);
    });

    it('should handle continuous playback with repeat-all mode', () => {
      queue.setRepeatMode('all');
      queue.jumpTo(2); // Start from last track

      expect(queue.getCurrentItem()).toEqual(mockTracks[2]);

      // Should loop back to first track
      const nextTrack = queue.next();
      expect(nextTrack).toEqual(mockTracks[0]);
      expect(queue.getCurrentIndex()).toBe(0);
    });
  });
});
