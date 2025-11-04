/**
 * E2E Favorites Tests
 * Tests favorites management against real Jellyfin server
 */

import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import { JellyfinClient } from '../../src/api/client.js';
import { AuthService } from '../../src/api/auth.js';
import { ItemsService } from '../../src/api/items.js';
import { FavoritesService } from '../../src/api/favorites.js';
import { loadTestConfig, delay, retryableLogin, AUTH_DELAY } from './setup.js';
import type { ClientInfo } from '../../src/models/types.js';

describe('E2E: Favorites Management', () => {
  let config: ReturnType<typeof loadTestConfig>;
  let client: JellyfinClient;
  let authService: AuthService;
  let itemsService: ItemsService;
  let favoritesService: FavoritesService;
  let userId: string;
  let testAlbumId: string | null = null;
  let testTrackId: string | null = null;

  const clientInfo: ClientInfo = {
    name: 'Jellyfin Mini Client E2E Tests',
    device: 'Test Runner',
    deviceId: 'e2e-test-favorites-id',
    version: '0.1.0-test',
  };

  beforeAll(async () => {
    await delay(AUTH_DELAY);

    config = loadTestConfig();
    client = new JellyfinClient(config.serverUrl, clientInfo);
    authService = new AuthService(client);

    const authResponse = await retryableLogin(
      () => authService.login(config.username, config.password),
      'Favorites tests authentication'
    );
    userId = authResponse.User.Id;

    if (!client.isAuthenticated()) {
      throw new Error('Authentication failed - token not set on client');
    }

    itemsService = new ItemsService(client);
    favoritesService = new FavoritesService(client);

    await delay(AUTH_DELAY);

    // Get test items
    const albumsResponse = await itemsService.getAlbums();
    if (albumsResponse.Items.length > 0) {
      testAlbumId = albumsResponse.Items[0].Id;

      const tracks = await itemsService.getAlbumTracks(testAlbumId);
      if (tracks.length > 0) {
        testTrackId = tracks[0].Id;
      }
    }
  });

  afterAll(async () => {
    // Clean up - ensure test items are unmarked as favorites
    if (testAlbumId) {
      try {
        await favoritesService.unmarkFavorite(testAlbumId);
      } catch (error) {
        // Ignore cleanup errors
      }
    }

    if (testTrackId) {
      try {
        await favoritesService.unmarkFavorite(testTrackId);
      } catch (error) {
        // Ignore cleanup errors
      }
    }
  });

  describe('Album Favorites', () => {
    it('should mark album as favorite', async () => {
      if (!testAlbumId) {
        console.warn('⚠️  No albums found in library, skipping test');
        return;
      }

      const result = await favoritesService.markFavorite(testAlbumId);

      expect(result).toBeDefined();
      expect(result.IsFavorite).toBe(true);
      expect(result.ItemId).toBe(testAlbumId);
    });

    it('should verify album is marked as favorite', async () => {
      if (!testAlbumId) {
        console.warn('⚠️  No albums found in library, skipping test');
        return;
      }

      // Mark as favorite first
      await favoritesService.markFavorite(testAlbumId);

      // Get the album and check favorite status
      const album = await itemsService.getItem(testAlbumId);
      expect(album.UserData?.IsFavorite).toBe(true);
    });

    it('should unmark album as favorite', async () => {
      if (!testAlbumId) {
        console.warn('⚠️  No albums found in library, skipping test');
        return;
      }

      // Mark as favorite first
      await favoritesService.markFavorite(testAlbumId);

      // Then unmark
      const result = await favoritesService.unmarkFavorite(testAlbumId);

      expect(result).toBeDefined();
      expect(result.IsFavorite).toBe(false);
      expect(result.ItemId).toBe(testAlbumId);
    });

    it('should get favorite albums', async () => {
      if (!testAlbumId) {
        console.warn('⚠️  No albums found in library, skipping test');
        return;
      }

      // Mark album as favorite
      await favoritesService.markFavorite(testAlbumId);

      // Small delay to allow Jellyfin database to update
      await delay(500);

      // Get favorite albums
      const favorites = await favoritesService.getFavoriteAlbums();

      expect(favorites).toBeDefined();
      expect(favorites.Items).toBeInstanceOf(Array);

      // Should include our test album
      const foundAlbum = favorites.Items.find(album => album.Id === testAlbumId);
      expect(foundAlbum).toBeDefined();
      expect(foundAlbum?.UserData?.IsFavorite).toBe(true);

      // Cleanup
      await favoritesService.unmarkFavorite(testAlbumId);
    });
  });

  describe('Track Favorites', () => {
    it('should mark track as favorite', async () => {
      if (!testTrackId) {
        console.warn('⚠️  No tracks found in library, skipping test');
        return;
      }

      const result = await favoritesService.markFavorite(testTrackId);

      expect(result).toBeDefined();
      expect(result.IsFavorite).toBe(true);
      expect(result.ItemId).toBe(testTrackId);
    });

    it('should unmark track as favorite', async () => {
      if (!testTrackId) {
        console.warn('⚠️  No tracks found in library, skipping test');
        return;
      }

      // Mark as favorite first
      await favoritesService.markFavorite(testTrackId);

      // Then unmark
      const result = await favoritesService.unmarkFavorite(testTrackId);

      expect(result).toBeDefined();
      expect(result.IsFavorite).toBe(false);
      expect(result.ItemId).toBe(testTrackId);
    });

    it('should get favorite tracks', async () => {
      if (!testTrackId) {
        console.warn('⚠️  No tracks found in library, skipping test');
        return;
      }

      // Mark track as favorite
      await favoritesService.markFavorite(testTrackId);

      // Small delay to allow Jellyfin database to update
      await delay(500);

      // Get favorite tracks
      const favorites = await favoritesService.getFavoriteTracks();

      expect(favorites).toBeDefined();
      expect(favorites.Items).toBeInstanceOf(Array);

      // Should include our test track
      const foundTrack = favorites.Items.find(track => track.Id === testTrackId);
      expect(foundTrack).toBeDefined();
      expect(foundTrack?.UserData?.IsFavorite).toBe(true);

      // Cleanup
      await favoritesService.unmarkFavorite(testTrackId);
    });
  });

  describe('Error Handling', () => {
    it('should handle marking invalid item ID as favorite', async () => {
      await expect(favoritesService.markFavorite('invalid-item-id-12345')).rejects.toThrow();
    });

    it('should handle unmarking invalid item ID as favorite', async () => {
      await expect(favoritesService.unmarkFavorite('invalid-item-id-12345')).rejects.toThrow();
    });

    it('should handle duplicate favorite marking gracefully', async () => {
      if (!testAlbumId) {
        console.warn('⚠️  No albums found in library, skipping test');
        return;
      }

      // Mark as favorite twice
      await favoritesService.markFavorite(testAlbumId);
      const result = await favoritesService.markFavorite(testAlbumId);

      // Should still succeed
      expect(result.IsFavorite).toBe(true);

      // Cleanup
      await favoritesService.unmarkFavorite(testAlbumId);
    });
  });
});
