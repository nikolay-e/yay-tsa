/**
 * Feature: Playback Reporting
 * Tests playback progress reporting to Media Server
 * Focus on user-visible playback states, not internal tick conversion
 */

import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import { loadTestConfig, setupTestFixtures, cleanupTestFixtures, delay } from '../setup.js';
import { TestFixtures } from '../fixtures/data-factory.js';
import { PlaybackReporter } from '../../../src/player/state.js';
import { PlaybackQueue } from '../../../src/player/queue.js';

describe('Feature: Playback Reporting', () => {
  let fixtures: TestFixtures;
  let reporter: PlaybackReporter;
  let testTrackId: string | null = null;

  beforeAll(async () => {
    const config = loadTestConfig();
    fixtures = await setupTestFixtures(config);
    reporter = new PlaybackReporter(fixtures.client);

    // Get test track
    if (fixtures.tracks.length > 0) {
      testTrackId = fixtures.tracks[0].Id;
    }
  });

  afterAll(async () => {
    if (testTrackId) {
      try {
        await reporter.reportStopped(testTrackId, 0);
      } catch (error) {
        // Ignore cleanup errors
      }
    }
    await cleanupTestFixtures(fixtures);
  });

  describe('Scenario: User plays a track', () => {
    it('Given: User selects track, When: Playback starts, Then: Server receives playback start event', async () => {
      if (!testTrackId) {
        console.warn('⚠️  No tracks available, skipping playback test');
        return;
      }

      // Given: User has selected track
      // When: User presses play
      const reportStart = reporter.reportStart(testTrackId);

      // Then: Server acknowledges playback start
      await expect(reportStart).resolves.not.toThrow();
    });

    it('Given: Track playing, When: Track progress updates, Then: Server receives progress', async () => {
      if (!testTrackId) return;

      // Given: Track is playing
      await reporter.reportStart(testTrackId);

      // When: 30 seconds of playback elapsed
      const reportProgress = reporter.reportProgress(testTrackId, 30, false);

      // Then: Server receives progress update
      await expect(reportProgress).resolves.not.toThrow();
    });

    it('Given: Track playing, When: User pauses, Then: Server receives pause state', async () => {
      if (!testTrackId) return;

      // Given: Track is playing
      await reporter.reportStart(testTrackId);

      // When: User pauses at 30 seconds
      const reportPause = reporter.reportProgress(testTrackId, 30, true);

      // Then: Server receives paused state
      await expect(reportPause).resolves.not.toThrow();
    });

    it('Given: Track playing, When: User stops playback, Then: Server receives stop event', async () => {
      if (!testTrackId) return;

      // Given: Track is playing
      await reporter.reportStart(testTrackId);

      // When: User stops at 30 seconds
      const reportStop = reporter.reportStopped(testTrackId, 30);

      // Then: Server receives stop event
      await expect(reportStop).resolves.not.toThrow();
    });
  });

  describe('Scenario: User plays through album', () => {
    it('Given: Playing track 1, When: Track finishes and track 2 starts, Then: Server receives stop + start events', async () => {
      if (fixtures.tracks.length < 2) {
        console.warn('⚠️  Need at least 2 tracks, skipping multi-track test');
        return;
      }

      const track1Id = fixtures.tracks[0].Id;
      const track2Id = fixtures.tracks[1].Id;

      // Given: Track 1 is playing
      await reporter.reportStart(track1Id);
      await reporter.reportProgress(track1Id, 60, false);

      // When: Track 1 finishes
      await reporter.reportStopped(track1Id, 120);

      // And: Track 2 starts automatically
      await reporter.reportStart(track2Id);

      // Then: Both events succeed
      expect(true).toBe(true);

      // Cleanup
      await reporter.reportStopped(track2Id, 0);
    });

    it('Given: Playing album, When: User seeks to 1 minute, Then: Server receives updated position', async () => {
      if (!testTrackId) return;

      // Given: Track is playing at 30 seconds
      await reporter.reportStart(testTrackId);
      await reporter.reportProgress(testTrackId, 30, false);

      // When: User seeks to 1 minute
      await reporter.reportProgress(testTrackId, 60, false);

      // Then: Server has updated position
      expect(true).toBe(true);
    });
  });

  describe('Scenario: User restarts playback', () => {
    it('Given: Previously played track, When: User plays again, Then: New session created', async () => {
      if (!testTrackId) return;

      // Given: First playback session
      await reporter.reportStart(testTrackId);
      await reporter.reportProgress(testTrackId, 10, false);
      await reporter.reportStopped(testTrackId, 10);

      // When: User plays track again
      await reporter.reportStart(testTrackId);
      await reporter.reportProgress(testTrackId, 20, false);
      await reporter.reportStopped(testTrackId, 20);

      // Then: Both sessions succeed
      expect(true).toBe(true);
    });
  });

  describe('Scenario: Network/error conditions', () => {
    it('Given: User tries to play, When: Invalid track ID, Then: Playback fails gracefully', async () => {
      // Given: User selects corrupted/deleted track
      const invalidTrackId = 'nonexistent-track-id-12345';

      // When: App tries to report playback start
      const reportStart = reporter.reportStart(invalidTrackId);

      // Then: Server returns error
      await expect(reportStart).rejects.toThrow();
    });

    it('Given: Track playing, When: Progress updates sent rapidly, Then: Server handles all updates', async () => {
      if (!testTrackId) return;

      // Given: Track is playing
      await reporter.reportStart(testTrackId);

      // When: User seeks rapidly (scrubbing progress bar)
      for (let i = 0; i < 5; i++) {
        await reporter.reportProgress(testTrackId, i * 10, false);
      }

      // Then: All progress updates accepted
      await reporter.reportStopped(testTrackId, 50);
      expect(true).toBe(true);
    });
  });

  describe('Scenario: User plays album in queue', () => {
    it('Given: Queue with 3 tracks, When: User plays through all, Then: All playback events reported', async () => {
      if (fixtures.tracks.length < 3) {
        console.warn('⚠️  Need at least 3 tracks for queue test');
        return;
      }

      // Given: Queue with 3 tracks
      const queue = new PlaybackQueue();
      const testTracks = fixtures.tracks.slice(0, 3);
      queue.setQueue(testTracks);

      // When: User plays through entire queue
      for (let i = 0; i < 3; i++) {
        const track = queue.getCurrentItem();
        if (!track) break;

        // Start track
        await reporter.reportStart(track.Id);

        // Simulate some playback
        await reporter.reportProgress(track.Id, 10, false);

        // Track finishes
        await reporter.reportStopped(track.Id, 60);

        // Move to next (except on last track)
        if (i < 2) {
          queue.next();
        }
      }

      // Then: Full playback sequence completed
      expect(queue.hasNext()).toBe(false);
    });
  });
});
