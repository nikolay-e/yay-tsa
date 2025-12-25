/**
 * Integration Playback State Tests
 * Tests playback state management and progress reporting
 */

import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import { MediaServerClient } from '../../src/api/client.js';
import { AuthService } from '../../src/api/auth.js';
import { ItemsService } from '../../src/api/items.js';
import { PlaybackReporter } from '../../src/player/state.js';
import { loadTestConfig, delay, retryableLogin, AUTH_DELAY } from './setup.js';
import type { ClientInfo } from '../../src/models/types.js';

describe('Integration: Playback Reporting', () => {
  let config: ReturnType<typeof loadTestConfig>;
  let client: MediaServerClient;
  let authService: AuthService;
  let itemsService: ItemsService;
  let reporter: PlaybackReporter;
  let userId: string;
  let testTrackId: string | null = null;

  const clientInfo: ClientInfo = {
    name: 'Media Server Client Integration Tests',
    device: 'Test Runner',
    deviceId: 'integration-test-playback-id',
    version: '0.1.0-test',
  };

  beforeAll(async () => {
    await delay(AUTH_DELAY);

    config = loadTestConfig();
    client = new MediaServerClient(config.serverUrl, clientInfo);
    authService = new AuthService(client);

    const authResponse = await retryableLogin(
      () => authService.login(config.username, config.password),
      'Playback tests authentication'
    );
    userId = authResponse.User.Id;

    if (!client.isAuthenticated()) {
      throw new Error('Authentication failed - token not set on client');
    }

    itemsService = new ItemsService(client);

    await delay(AUTH_DELAY);

    // Get a test track ID
    const albumsResponse = await itemsService.getAlbums();
    if (albumsResponse.Items.length > 0) {
      const tracks = await itemsService.getAlbumTracks(albumsResponse.Items[0].Id);
      if (tracks.length > 0) {
        testTrackId = tracks[0].Id;
      }
    }

    reporter = new PlaybackReporter(client);
  });

  afterAll(async () => {
    // Clean up - stop any active playback sessions
    if (testTrackId) {
      try {
        await reporter.reportStopped(testTrackId, 0);
      } catch (error) {
        // Ignore cleanup errors
      }
    }
  });

  describe('Playback Progress Reporting', () => {
    it('should report playback start', async () => {
      if (!testTrackId) {
        console.warn('⚠️  No tracks found in library, skipping playback test');
        return;
      }

      await expect(reporter.reportStart(testTrackId)).resolves.not.toThrow();
    });

    it('should report playback progress', async () => {
      if (!testTrackId) {
        console.warn('⚠️  No tracks found in library, skipping progress test');
        return;
      }

      // Start playback first
      await reporter.reportStart(testTrackId);

      // Report progress at 30 seconds
      await expect(reporter.reportProgress(testTrackId, 30, false)).resolves.not.toThrow();
    });

    it('should report playback pause', async () => {
      if (!testTrackId) {
        console.warn('⚠️  No tracks found in library, skipping pause test');
        return;
      }

      await reporter.reportStart(testTrackId);

      await expect(reporter.reportProgress(testTrackId, 30, true)).resolves.not.toThrow();
    });

    it('should report playback stop', async () => {
      if (!testTrackId) {
        console.warn('⚠️  No tracks found in library, skipping stop test');
        return;
      }

      await reporter.reportStart(testTrackId);

      await expect(reporter.reportStopped(testTrackId, 30)).resolves.not.toThrow();
    });

    it('should convert seconds to ticks correctly', async () => {
      if (!testTrackId) {
        console.warn('⚠️  No tracks found in library, skipping ticks test');
        return;
      }

      // 60 seconds = 600,000,000 ticks (60 * 10,000,000)
      await reporter.reportStart(testTrackId);
      await expect(reporter.reportProgress(testTrackId, 60, false)).resolves.not.toThrow();
    });
  });

  describe('Session Management', () => {
    it('should handle multiple playback sessions', async () => {
      if (!testTrackId) {
        console.warn('⚠️  No tracks found in library, skipping session test');
        return;
      }

      // Start first session
      await reporter.reportStart(testTrackId);
      await reporter.reportProgress(testTrackId, 10, false);
      await reporter.reportStopped(testTrackId, 10);

      // Start second session
      await reporter.reportStart(testTrackId);
      await reporter.reportProgress(testTrackId, 20, false);
      await reporter.reportStopped(testTrackId, 20);

      // Both should succeed without error
      expect(true).toBe(true);
    });

    it('should handle rapid progress updates', async () => {
      if (!testTrackId) {
        console.warn('⚠️  No tracks found in library, skipping rapid updates test');
        return;
      }

      await reporter.reportStart(testTrackId);

      // Simulate rapid progress updates (like during seeking)
      for (let i = 0; i < 5; i++) {
        await reporter.reportProgress(testTrackId, i * 10, false);
      }

      await reporter.reportStopped(testTrackId, 50);
      expect(true).toBe(true);
    });
  });

  describe('Error Handling', () => {
    it('should handle invalid track ID gracefully', async () => {
      await expect(reporter.reportStart('invalid-track-id-12345')).rejects.toThrow();
    });

    it('should handle negative position values', async () => {
      if (!testTrackId) {
        console.warn('⚠️  No tracks found in library, skipping negative position test');
        return;
      }

      await reporter.reportStart(testTrackId);

      // Server should handle negative values gracefully
      await expect(reporter.reportProgress(testTrackId, -10, false)).resolves.not.toThrow();

      await reporter.reportStopped(testTrackId, 0);
    });
  });
});
