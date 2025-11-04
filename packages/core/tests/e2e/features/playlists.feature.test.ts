/**
 * Feature: Playlist Management
 * Tests user's ability to create and manage playlists
 * Focus on CRUD operations and user workflows
 */

import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import {
  loadTestConfig,
  setupTestFixtures,
  cleanupTestFixtures,
  createScenario,
} from '../setup.js';
import { TestFixtures } from '../fixtures/data-factory.js';
import { ThenPlaylist } from '../fixtures/scenarios.js';

describe('Feature: Playlist Management', () => {
  let fixtures: TestFixtures;
  let scenario: ReturnType<typeof createScenario>;
  let createdPlaylistIds: string[] = [];

  beforeAll(async () => {
    const config = loadTestConfig();
    fixtures = await setupTestFixtures(config);
    scenario = createScenario(fixtures);
  });

  afterAll(async () => {
    // Cleanup all created playlists
    for (const playlistId of createdPlaylistIds) {
      try {
        await fixtures.playlistsService.deletePlaylist(playlistId);
      } catch (error) {
        // Ignore cleanup errors
      }
    }

    await cleanupTestFixtures(fixtures);
  });

  describe('Scenario: User creates a new playlist', () => {
    it('Given: User wants to organize music, When: Creates empty playlist, Then: Playlist created', async () => {
      // Given: User is in library
      await scenario.given.user.isAuthenticated();

      // When: User creates new playlist "My Chill Mix"
      const playlistName = `Test Playlist ${Date.now()}`;
      const playlist = await scenario.when.user.createsPlaylist(playlistName, []);

      createdPlaylistIds.push(playlist.Id);

      // Then: Playlist exists with correct name
      ThenPlaylist.playlistIsCreated(playlist, playlistName);
    });

    it('Given: User selects tracks, When: Creates playlist with tracks, Then: Playlist contains tracks', async () => {
      // Given: User has selected 3 tracks
      const trackIds = fixtures.tracks.slice(0, 3).map(t => t.Id);

      // When: User creates playlist with these tracks
      const playlistName = `Tracks Playlist ${Date.now()}`;
      const playlist = await scenario.when.user.createsPlaylist(playlistName, trackIds);

      createdPlaylistIds.push(playlist.Id);

      // Then: Playlist has 3 tracks
      const items = await fixtures.playlistsService.getPlaylistItems(playlist.Id);
      ThenPlaylist.playlistHasTracks(items, 3);
    });
  });

  describe('Scenario: User adds tracks to playlist', () => {
    it('Given: Empty playlist, When: User adds tracks, Then: Playlist contains tracks', async () => {
      // Given: User has empty playlist
      const playlistName = `Add Tracks Test ${Date.now()}`;
      const playlist = await scenario.when.user.createsPlaylist(playlistName, []);
      createdPlaylistIds.push(playlist.Id);

      // When: User adds 2 tracks
      const trackIds = fixtures.tracks.slice(0, 2).map(t => t.Id);
      await scenario.when.user.addsTracksToPlaylist(playlist.Id, trackIds);

      // Then: Playlist has 2 tracks
      const items = await fixtures.playlistsService.getPlaylistItems(playlist.Id);
      ThenPlaylist.playlistHasTracks(items, 2);
    });

    it('Given: Playlist with tracks, When: Adds duplicate track, Then: Track appears twice', async () => {
      // Given: Playlist with 1 track
      const trackIds = [fixtures.tracks[0].Id];
      const playlistName = `Duplicate Test ${Date.now()}`;
      const playlist = await scenario.when.user.createsPlaylist(playlistName, trackIds);
      createdPlaylistIds.push(playlist.Id);

      // When: User adds same track again
      await scenario.when.user.addsTracksToPlaylist(playlist.Id, trackIds);

      // Then: Playlist has 2 tracks (same track twice)
      const items = await fixtures.playlistsService.getPlaylistItems(playlist.Id);
      ThenPlaylist.playlistHasTracks(items, 2);
    });
  });

  describe('Scenario: User removes tracks from playlist', () => {
    it('Given: Playlist with 3 tracks, When: Removes middle track, Then: Playlist has 2 tracks', async () => {
      // Given: Playlist with 3 tracks
      const trackIds = fixtures.tracks.slice(0, 3).map(t => t.Id);
      const playlistName = `Remove Test ${Date.now()}`;
      const playlist = await scenario.when.user.createsPlaylist(playlistName, trackIds);
      createdPlaylistIds.push(playlist.Id);

      // When: User removes track 2
      const items = await fixtures.playlistsService.getPlaylistItems(playlist.Id);
      const entryIdToRemove = items.Items[1].PlaylistItemId!;

      await scenario.when.user.removesTracksFromPlaylist(playlist.Id, [entryIdToRemove]);

      // Then: Playlist has 2 tracks
      const updatedItems = await fixtures.playlistsService.getPlaylistItems(playlist.Id);
      ThenPlaylist.playlistHasTracks(updatedItems, 2);
    });
  });

  describe('Scenario: User reorders playlist', () => {
    it('Given: Playlist with ordered tracks, When: Moves last track to first, Then: Order changes', async () => {
      // Given: Playlist with 3 tracks
      const trackIds = fixtures.tracks.slice(0, 3).map(t => t.Id);
      const playlistName = `Reorder Test ${Date.now()}`;
      const playlist = await scenario.when.user.createsPlaylist(playlistName, trackIds);
      createdPlaylistIds.push(playlist.Id);

      const itemsBefore = await fixtures.playlistsService.getPlaylistItems(playlist.Id);
      const lastTrack = itemsBefore.Items[2];

      // When: User drags last track to first position
      await fixtures.playlistsService.movePlaylistItem(playlist.Id, lastTrack.PlaylistItemId!, 0);

      // Then: Track order changed
      const itemsAfter = await fixtures.playlistsService.getPlaylistItems(playlist.Id);
      expect(itemsAfter.Items[0].Id).toBe(lastTrack.Id);
    });
  });

  describe('Scenario: User deletes playlist', () => {
    it('Given: User has playlist, When: Deletes playlist, Then: Playlist removed', async () => {
      // Given: User has playlist
      const playlistName = `Delete Test ${Date.now()}`;
      const playlist = await scenario.when.user.createsPlaylist(playlistName, []);

      // When: User deletes playlist
      await fixtures.playlistsService.deletePlaylist(playlist.Id);

      // Then: Playlist no longer exists
      await ThenPlaylist.playlistIsDeleted(fixtures.playlistsService, playlist.Id);
    });
  });

  describe('Scenario: User browses all playlists', () => {
    it('Given: User has playlists, When: Opens playlists view, Then: Sees all playlists', async () => {
      // Given: User has created playlists
      const playlist1 = await scenario.when.user.createsPlaylist(`Playlist 1 ${Date.now()}`, []);
      const playlist2 = await scenario.when.user.createsPlaylist(`Playlist 2 ${Date.now()}`, []);

      createdPlaylistIds.push(playlist1.Id, playlist2.Id);

      // When: User opens "My Playlists"
      const allPlaylists = await fixtures.playlistsService.getPlaylists();

      // Then: Both playlists appear
      const foundPlaylist1 = allPlaylists.Items.some(p => p.Id === playlist1.Id);
      const foundPlaylist2 = allPlaylists.Items.some(p => p.Id === playlist2.Id);

      expect(foundPlaylist1).toBe(true);
      expect(foundPlaylist2).toBe(true);
    });
  });

  describe('Scenario: User plays playlist', () => {
    it('Given: Playlist with 5 tracks, When: User plays playlist, Then: Queue contains all tracks', async () => {
      // Given: Playlist with 5 tracks
      const trackIds = fixtures.tracks.slice(0, 5).map(t => t.Id);
      const playlistName = `Play Test ${Date.now()}`;
      const playlist = await scenario.when.user.createsPlaylist(playlistName, trackIds);
      createdPlaylistIds.push(playlist.Id);

      // When: User clicks "Play Playlist"
      const items = await fixtures.playlistsService.getPlaylistItems(playlist.Id);

      // Then: Playlist has all 5 tracks
      ThenPlaylist.playlistHasTracks(items, 5);

      // And: User can load them into queue
      const PlaybackQueue = (await import('../../../src/player/queue.js')).PlaybackQueue;
      const queue = new PlaybackQueue();
      queue.setQueue(items.Items);

      expect(queue.getAllItems()).toHaveLength(5);
      expect(queue.getCurrentItem()).toEqual(items.Items[0]);
    });
  });
});
