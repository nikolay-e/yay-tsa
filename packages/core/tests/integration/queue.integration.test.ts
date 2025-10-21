/**
 * Integration Tests: Playback Queue
 * Tests playback queue with real tracks from Jellyfin server
 */

import { describe, it, expect, beforeAll, beforeEach } from 'vitest';
import { JellyfinClient } from '../../src/api/client.js';
import { ItemsService } from '../../src/api/items.js';
import { PlaybackQueue } from '../../src/player/queue.js';
import { integrationConfig } from './setup.js';
import type { AudioItem } from '../../src/models/types.js';

describe.skipIf(!integrationConfig.useApiKey)('Playback Queue Integration', () => {
  let client: JellyfinClient;
  let itemsService: ItemsService;
  let queue: PlaybackQueue;
  let testTracks: AudioItem[] = [];

  beforeAll(async () => {
    client = new JellyfinClient(integrationConfig.serverUrl, {
      name: integrationConfig.clientName,
      device: integrationConfig.deviceName,
      deviceId: integrationConfig.deviceId,
      version: '0.1.0',
    });
    await client.initWithApiKey(integrationConfig.apiKey);
    itemsService = new ItemsService(client);

    // Get real tracks from server
    const result = await itemsService.getTracks({ limit: 10 });
    testTracks = result.Items;

    if (testTracks.length === 0) {
      console.warn('⚠️  No tracks found in library. Some tests may be skipped.');
    } else {
      console.log(`  ℹ️  Loaded ${testTracks.length} tracks for queue tests`);
    }
  });

  beforeEach(() => {
    queue = new PlaybackQueue();
  });

  describe('Queue with Real Tracks', () => {
    it('should create queue from real tracks', () => {
      if (testTracks.length === 0) {
        console.log('  ⊘  Skipped: No tracks available');
        return;
      }

      queue.setQueue(testTracks);

      expect(queue.getAllItems()).toHaveLength(testTracks.length);
      expect(queue.getCurrentItem()?.Id).toBe(testTracks[0].Id);

      console.log(`  ℹ️  Queue created with ${testTracks.length} tracks`);
      console.log(`  ℹ️  First track: "${queue.getCurrentItem()?.Name}"`);
    });

    it('should navigate through real tracks', () => {
      if (testTracks.length < 2) {
        console.log('  ⊘  Skipped: Need at least 2 tracks');
        return;
      }

      queue.setQueue(testTracks);

      const first = queue.getCurrentItem();
      const second = queue.next();
      const backToFirst = queue.previous();

      expect(first?.Id).toBe(testTracks[0].Id);
      expect(second?.Id).toBe(testTracks[1].Id);
      expect(backToFirst?.Id).toBe(testTracks[0].Id);

      console.log(`  ℹ️  Navigated: "${first?.Name}" → "${second?.Name}" → "${backToFirst?.Name}"`);
    });

    it('should shuffle real tracks', () => {
      if (testTracks.length < 5) {
        console.log('  ⊘  Skipped: Need at least 5 tracks for meaningful shuffle');
        return;
      }

      queue.setQueue(testTracks);
      const originalOrder = queue.getAllItems().map(t => t.Id);

      queue.setShuffleMode('on');
      const shuffledOrder = queue.getAllItems().map(t => t.Id);

      // At least some tracks should be in different positions
      let different = 0;
      for (let i = 0; i < originalOrder.length; i++) {
        if (originalOrder[i] !== shuffledOrder[i]) {
          different++;
        }
      }

      expect(different).toBeGreaterThan(0);
      console.log(`  ℹ️  Shuffled: ${different}/${originalOrder.length} tracks moved`);

      // Current track should stay the same
      expect(queue.getCurrentItem()?.Id).toBe(testTracks[0].Id);
    });

    it('should handle repeat modes with real tracks', () => {
      if (testTracks.length === 0) {
        console.log('  ⊘  Skipped: No tracks available');
        return;
      }

      queue.setQueue(testTracks);

      // Repeat one
      queue.setRepeatMode('one');
      const current = queue.getCurrentItem();
      const repeated = queue.next();

      expect(repeated?.Id).toBe(current?.Id);
      console.log(`  ℹ️  Repeat one: "${current?.Name}" stays on repeat`);

      // Repeat all
      queue.setRepeatMode('all');
      queue.jumpTo(testTracks.length - 1);
      const lastTrack = queue.getCurrentItem();
      const loopedToFirst = queue.next();

      expect(loopedToFirst?.Id).toBe(testTracks[0].Id);
      console.log(`  ℹ️  Repeat all: "${lastTrack?.Name}" → "${loopedToFirst?.Name}"`);
    });

    it('should get stream URLs for queued tracks', () => {
      if (testTracks.length === 0) {
        console.log('  ⊘  Skipped: No tracks available');
        return;
      }

      queue.setQueue(testTracks);
      const track = queue.getCurrentItem();

      if (track) {
        const streamUrl = client.getStreamUrl(track.Id);

        expect(streamUrl).toBeDefined();
        expect(streamUrl).toContain(track.Id);
        expect(streamUrl).toContain('api_key');

        console.log(`  ℹ️  Stream URL: ${streamUrl.substring(0, 80)}...`);
      }
    });
  });

  describe('Queue from Album', () => {
    it('should create queue from album tracks', async () => {
      // Get an album
      const albums = await itemsService.getAlbums({ limit: 1 });

      if (albums.Items.length === 0) {
        console.log('  ⊘  Skipped: No albums available');
        return;
      }

      const album = albums.Items[0];
      console.log(`  ℹ️  Testing with album: "${album.Name}"`);

      // Get tracks from this album
      const tracks = await itemsService.getTracks({
        filters: {
          parentId: album.Id,
        },
        sortBy: 'SortName',
      });

      if (tracks.Items.length === 0) {
        console.log('  ⊘  Album has no tracks');
        return;
      }

      queue.setQueue(tracks.Items);

      expect(queue.getAllItems()).toHaveLength(tracks.Items.length);
      console.log(`  ℹ️  Album queue created with ${tracks.Items.length} tracks`);

      // Play through first 3 tracks
      const playedTracks = [];
      playedTracks.push(queue.getCurrentItem()?.Name);
      queue.next();
      playedTracks.push(queue.getCurrentItem()?.Name);
      queue.next();
      playedTracks.push(queue.getCurrentItem()?.Name);

      console.log(`  ℹ️  Played: ${playedTracks.join(' → ')}`);
    });
  });

  describe('Queue from Artist', () => {
    it('should create queue from artist tracks', async () => {
      // Get an artist
      const artists = await itemsService.getArtists({ limit: 1 });

      if (artists.Items.length === 0) {
        console.log('  ⊘  Skipped: No artists available');
        return;
      }

      const artist = artists.Items[0];
      console.log(`  ℹ️  Testing with artist: "${artist.Name}"`);

      // Get tracks from this artist
      const tracks = await itemsService.getTracks({
        filters: {
          artistIds: [artist.Id],
        },
        limit: 20,
      });

      if (tracks.Items.length === 0) {
        console.log('  ⊘  Artist has no tracks');
        return;
      }

      queue.setQueue(tracks.Items);

      expect(queue.getAllItems()).toHaveLength(tracks.Items.length);
      console.log(`  ℹ️  Artist queue created with ${tracks.Items.length} tracks`);
    });
  });

  describe('Queue Manipulation', () => {
    it('should add tracks to existing queue', async () => {
      if (testTracks.length < 5) {
        console.log('  ⊘  Skipped: Need at least 5 tracks');
        return;
      }

      // Start with first 3 tracks
      queue.setQueue(testTracks.slice(0, 3));
      const initialLength = queue.getAllItems().length;

      // Add more tracks
      queue.addMultipleToQueue(testTracks.slice(3, 5));

      expect(queue.getAllItems()).toHaveLength(initialLength + 2);
      console.log(`  ℹ️  Queue extended: ${initialLength} → ${queue.getAllItems().length} tracks`);
    });

    it('should remove track from queue', () => {
      if (testTracks.length < 3) {
        console.log('  ⊘  Skipped: Need at least 3 tracks');
        return;
      }

      queue.setQueue(testTracks.slice(0, 3));
      const trackToRemove = queue.getAllItems()[1];

      queue.removeAt(1);

      expect(queue.getAllItems()).toHaveLength(2);
      expect(queue.getAllItems().find(t => t.Id === trackToRemove.Id)).toBeUndefined();

      console.log(`  ℹ️  Removed "${trackToRemove.Name}" from queue`);
    });

    it('should jump to specific track', () => {
      if (testTracks.length < 5) {
        console.log('  ⊘  Skipped: Need at least 5 tracks');
        return;
      }

      queue.setQueue(testTracks);

      const targetTrack = queue.jumpTo(3);

      expect(targetTrack?.Id).toBe(testTracks[3].Id);
      expect(queue.getCurrentIndex()).toBe(3);

      console.log(`  ℹ️  Jumped to track #4: "${targetTrack?.Name}"`);
    });
  });

  describe('Queue State', () => {
    it('should preserve queue state through mode changes', () => {
      if (testTracks.length < 3) {
        console.log('  ⊘  Skipped: Need at least 3 tracks');
        return;
      }

      queue.setQueue(testTracks);
      queue.jumpTo(1); // Go to second track

      const currentBefore = queue.getCurrentItem();

      // Change modes
      queue.setShuffleMode('on');
      queue.setRepeatMode('all');

      const currentAfter = queue.getCurrentItem();

      // Current track should not change
      expect(currentAfter?.Id).toBe(currentBefore?.Id);

      console.log(`  ℹ️  Current track preserved: "${currentBefore?.Name}"`);
    });

    it('should handle empty queue gracefully', () => {
      queue.setQueue([]);

      expect(queue.getAllItems()).toHaveLength(0);
      expect(queue.getCurrentItem()).toBeNull();
      expect(queue.next()).toBeNull();
      expect(queue.previous()).toBeNull();

      console.log('  ℹ️  Empty queue handled gracefully');
    });
  });
});
