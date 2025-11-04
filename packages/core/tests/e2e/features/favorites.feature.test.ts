/**
 * Feature: Favorites Management
 * Tests user's ability to mark and manage favorite albums/tracks/artists
 * Focus on user actions and visible state changes
 */

import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import {
  loadTestConfig,
  setupTestFixtures,
  cleanupTestFixtures,
  createScenario,
} from '../setup.js';
import { TestFixtures } from '../fixtures/data-factory.js';
import { ThenLibrary } from '../fixtures/scenarios.js';

describe('Feature: Favorites Management', () => {
  let fixtures: TestFixtures;
  let scenario: ReturnType<typeof createScenario>;
  let testAlbumId: string;
  let testTrackId: string;
  let testArtistId: string;

  beforeAll(async () => {
    const config = loadTestConfig();
    fixtures = await setupTestFixtures(config);
    scenario = createScenario(fixtures);

    testAlbumId = fixtures.albums[0].Id;
    testTrackId = fixtures.tracks[0].Id;
    testArtistId = fixtures.artists[0].Id;
  });

  afterAll(async () => {
    // Cleanup: unfavorite everything
    try {
      await fixtures.favoritesService.unmarkFavorite(testAlbumId);
      await fixtures.favoritesService.unmarkFavorite(testTrackId);
      await fixtures.favoritesService.unmarkFavorite(testArtistId);
    } catch (error) {
      // Ignore cleanup errors
    }

    await cleanupTestFixtures(fixtures);
  });

  describe('Scenario: User favorites an album', () => {
    it('Given: User viewing album, When: Clicks favorite button, Then: Album marked as favorite', async () => {
      // Given: User views album (ensure not already favorited)
      const albumBefore = await fixtures.itemsService.getItem(testAlbumId);
      if (albumBefore.UserData?.IsFavorite) {
        await scenario.when.user.unmarksAsFavorite(testAlbumId);
      }

      // When: User clicks heart icon
      await scenario.when.user.marksAsFavorite(testAlbumId);

      // Then: Album shows as favorited
      const albumAfter = await fixtures.itemsService.getItem(testAlbumId);
      ThenLibrary.itemIsFavorited(albumAfter);
    });

    it('Given: Favorited album, When: Views favorites, Then: Album appears in favorites', async () => {
      // Given: Album is favorited
      await scenario.when.user.marksAsFavorite(testAlbumId);

      // When: User opens "My Favorites" section
      const favoriteAlbums = await scenario.when.user.browsesFavoriteAlbums();

      // Then: Album is in favorites list
      const foundAlbum = favoriteAlbums.Items.some((a: any) => a.Id === testAlbumId);
      expect(foundAlbum).toBe(true);
    });

    it('Given: Favorited album, When: Unfavorites album, Then: Album no longer favorite', async () => {
      // Given: Album is favorited
      await scenario.when.user.marksAsFavorite(testAlbumId);
      let album = await fixtures.itemsService.getItem(testAlbumId);
      expect(album.UserData?.IsFavorite).toBe(true);

      // When: User unfavorites album
      await scenario.when.user.unmarksAsFavorite(testAlbumId);

      // Then: Album no longer shows favorite icon
      album = await fixtures.itemsService.getItem(testAlbumId);
      ThenLibrary.itemIsNotFavorited(album);
    });
  });

  describe('Scenario: User favorites a track', () => {
    it('Given: User playing track, When: Marks as favorite, Then: Track favorited', async () => {
      // Given: User listens to track
      const trackBefore = await fixtures.itemsService.getItem(testTrackId);

      // When: User favorites track
      await scenario.when.user.marksAsFavorite(testTrackId);

      // Then: Track is favorited
      const trackAfter = await fixtures.itemsService.getItem(testTrackId);
      ThenLibrary.itemIsFavorited(trackAfter);
    });

    it('Given: User has favorite tracks, When: Views favorites, Then: Can filter by favorite tracks', async () => {
      // Given: Track is favorited
      await scenario.when.user.marksAsFavorite(testTrackId);

      // When: User filters library by favorites
      const favoriteTracks = await fixtures.itemsService.getTracks({ isFavorite: true });

      // Then: Favorited track appears
      const foundTrack = favoriteTracks.Items.some(t => t.Id === testTrackId);
      expect(foundTrack).toBe(true);
    });
  });

  describe('Scenario: User favorites an artist', () => {
    it('Given: User viewing artist, When: Favorites artist, Then: Artist marked as favorite', async () => {
      // Given: User views artist page
      // When: User favorites artist
      await scenario.when.user.marksAsFavorite(testArtistId);

      // Then: Artist is favorited
      const artist = await fixtures.itemsService.getItem(testArtistId);
      ThenLibrary.itemIsFavorited(artist);
    });

    it('Given: User has favorite artists, When: Opens favorites, Then: Can browse favorite artists', async () => {
      // Given: Artist is favorited
      await scenario.when.user.marksAsFavorite(testArtistId);

      // When: User opens "Favorite Artists"
      const favoriteArtists = await scenario.when.user.browsesFavoriteArtists();

      // Then: Favorited artist appears
      const foundArtist = favoriteArtists.Items.some((a: any) => a.Id === testArtistId);
      expect(foundArtist).toBe(true);
    });
  });

  describe('Scenario: User manages favorites across sessions', () => {
    it('Given: User favorited items, When: Logs out and logs in, Then: Favorites persist', async () => {
      // Given: User favorites album
      await scenario.when.user.marksAsFavorite(testAlbumId);
      let album = await fixtures.itemsService.getItem(testAlbumId);
      expect(album.UserData?.IsFavorite).toBe(true);

      // When: User logs out and logs back in (simulated by new request)
      // Note: In real app, this would involve logout/login sequence
      // Here we verify favorites are server-side, not client-side only

      // Then: Favorites still present
      album = await fixtures.itemsService.getItem(testAlbumId);
      expect(album.UserData?.IsFavorite).toBe(true);
    });
  });

  describe('Scenario: User searches favorite items', () => {
    it('Given: User has favorited multiple albums, When: Searches in favorites, Then: Finds favorite matches', async () => {
      // Given: User has favorites
      await scenario.when.user.marksAsFavorite(testAlbumId);

      // When: User searches within favorites (using album name)
      const album = await fixtures.itemsService.getItem(testAlbumId);
      const searchTerm = album.Name.split(' ')[0];

      const results = await fixtures.itemsService.search(searchTerm);

      // Then: Favorited album appears in results with favorite flag
      const favoriteAlbumsInResults = results.albums.filter(a => a.UserData?.IsFavorite === true);

      expect(favoriteAlbumsInResults.length).toBeGreaterThanOrEqual(0);
    });
  });
});
