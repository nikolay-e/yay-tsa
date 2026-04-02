/**
 * Integration Playlists Tests
 * Tests playlist management against real Media Server
 */

import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import { MediaServerClient } from '../../src/api/api.client.js';
import { AuthService } from '../../src/api/auth.service.js';
import { ItemsService } from '../../src/api/items.service.js';
import { PlaylistsService } from '../../src/api/playlists.service.js';
import { loadTestConfig, delay, retryableLogin, AUTH_DELAY } from './setup.js';
import type { ClientInfo } from '../../src/index.js';

describe('Integration: Playlists Management', () => {
  let config: ReturnType<typeof loadTestConfig>;
  let client: MediaServerClient;
  let authService: AuthService;
  let itemsService: ItemsService;
  let playlistsService: PlaylistsService;
  let testTrackIds: string[] = [];

  const clientInfo: ClientInfo = {
    name: 'Media Server Client Integration Tests',
    device: 'Test Runner',
    deviceId: 'integration-test-playlists-id',
    version: '0.1.0-test',
  };

  beforeAll(async () => {
    await delay(AUTH_DELAY);

    config = loadTestConfig();
    client = new MediaServerClient(config.serverUrl, clientInfo);
    authService = new AuthService(client);

    await retryableLogin(
      () => authService.login(config.username, config.password),
      'Playlists tests authentication'
    );

    // Verify token is set
    if (!client.isAuthenticated()) {
      throw new Error('Authentication failed - token not set on client');
    }

    // Initialize services
    itemsService = new ItemsService(client);
    playlistsService = new PlaylistsService(client);

    // Get some test tracks to use in playlists
    const tracksResult = await itemsService.getTracks({ limit: 5 });
    if (tracksResult.Items.length < 3) {
      throw new Error('Test server must have at least 3 tracks for playlist tests');
    }
    testTrackIds = tracksResult.Items.slice(0, 3).map(track => track.Id);

    // Add delay after setup
    await delay(AUTH_DELAY);
  });

  afterAll(async () => {
    await delay(AUTH_DELAY);
  });

  it('should create an empty playlist', async () => {
    const playlistName = `Test Playlist ${Date.now()}`;

    const playlist = await playlistsService.createPlaylist({
      name: playlistName,
      mediaType: 'Audio',
    });

    try {
      expect(playlist).toBeDefined();
      expect(playlist.Id).toBeDefined();
      expect(playlist.Name).toBe(playlistName);
      expect(playlist.Type).toBe('Playlist');
    } finally {
      await playlistsService.deletePlaylist(playlist.Id).catch(() => {});
    }
  });

  it('should get playlist details', async () => {
    const playlistName = `Details Test Playlist ${Date.now()}`;
    const created = await playlistsService.createPlaylist({
      name: playlistName,
      mediaType: 'Audio',
    });

    try {
      const playlist = await playlistsService.getPlaylist(created.Id);

      expect(playlist).toBeDefined();
      expect(playlist.Id).toBe(created.Id);
      expect(playlist.Type).toBe('Playlist');
      expect(playlist.Name).toBe(playlistName);
    } finally {
      await playlistsService.deletePlaylist(created.Id).catch(() => {});
    }
  });

  it('should add items to playlist', async () => {
    expect(testTrackIds.length).toBeGreaterThanOrEqual(2);

    const created = await playlistsService.createPlaylist({
      name: `Add Items Test ${Date.now()}`,
      mediaType: 'Audio',
    });

    try {
      const itemsToAdd = testTrackIds.slice(0, 2);
      await playlistsService.addItemsToPlaylist(created.Id, itemsToAdd);

      const items = await playlistsService.getPlaylistItems(created.Id);

      expect(items).toBeDefined();
      expect(items.Items.length).toBe(2);
      expect(items.Items[0].Id).toBeDefined();
      expect(items.Items[1].Id).toBeDefined();
    } finally {
      await playlistsService.deletePlaylist(created.Id).catch(() => {});
    }
  });

  it('should get playlist items', async () => {
    expect(testTrackIds.length).toBeGreaterThanOrEqual(2);

    const created = await playlistsService.createPlaylist({
      name: `Get Items Test ${Date.now()}`,
      mediaType: 'Audio',
      itemIds: testTrackIds.slice(0, 2),
    });

    try {
      const items = await playlistsService.getPlaylistItems(created.Id);

      expect(items).toBeDefined();
      expect(items.Items).toBeInstanceOf(Array);
      expect(items.Items.length).toBe(2);
      expect(items.TotalRecordCount).toBe(2);

      const firstItem = items.Items[0];
      expect(firstItem.Id).toBeDefined();
      expect(firstItem.Type).toBe('Audio');
      expect(firstItem.Name).toBeDefined();
    } finally {
      await playlistsService.deletePlaylist(created.Id).catch(() => {});
    }
  });

  it('should add more items to existing playlist', async () => {
    expect(testTrackIds.length).toBeGreaterThanOrEqual(3);

    const created = await playlistsService.createPlaylist({
      name: `Add More Items Test ${Date.now()}`,
      mediaType: 'Audio',
      itemIds: testTrackIds.slice(0, 2),
    });

    try {
      const beforeItems = await playlistsService.getPlaylistItems(created.Id);
      const beforeCount = beforeItems.Items.length;

      await playlistsService.addItemsToPlaylist(created.Id, [testTrackIds[2]]);

      const afterItems = await playlistsService.getPlaylistItems(created.Id);
      expect(afterItems.Items.length).toBe(beforeCount + 1);
    } finally {
      await playlistsService.deletePlaylist(created.Id).catch(() => {});
    }
  });

  it('should remove items from playlist', async () => {
    expect(testTrackIds.length).toBeGreaterThanOrEqual(2);

    const created = await playlistsService.createPlaylist({
      name: `Remove Items Test ${Date.now()}`,
      mediaType: 'Audio',
      itemIds: testTrackIds.slice(0, 2),
    });

    try {
      const beforeItems = await playlistsService.getPlaylistItems(created.Id);
      expect(beforeItems.Items.length).toBe(2);

      const entryIdToRemove = beforeItems.Items[0].PlaylistItemId!;

      await playlistsService.removeItemsFromPlaylist(created.Id, [entryIdToRemove]);

      const afterItems = await playlistsService.getPlaylistItems(created.Id);
      expect(afterItems.Items.length).toBe(1);
    } finally {
      await playlistsService.deletePlaylist(created.Id).catch(() => {});
    }
  });

  it('should create playlist with initial items', async () => {
    expect(testTrackIds.length).toBeGreaterThanOrEqual(2);

    const playlistName = `Test Playlist with Items ${Date.now()}`;
    const playlist = await playlistsService.createPlaylist({
      name: playlistName,
      mediaType: 'Audio',
      itemIds: testTrackIds.slice(0, 2),
    });

    expect(playlist).toBeDefined();
    expect(playlist.Id).toBeDefined();

    // Verify items were added
    const items = await playlistsService.getPlaylistItems(playlist.Id);
    expect(items.Items.length).toBe(2);

    // Clean up this playlist
    await playlistsService.deletePlaylist(playlist.Id);
  });

  it('should move/reorder items in playlist', async () => {
    expect(testTrackIds.length).toBeGreaterThanOrEqual(2);

    const created = await playlistsService.createPlaylist({
      name: `Reorder Test ${Date.now()}`,
      mediaType: 'Audio',
      itemIds: testTrackIds.slice(0, 2),
    });

    try {
      const beforeItems = await playlistsService.getPlaylistItems(created.Id);
      expect(beforeItems.Items.length).toBe(2);

      const firstItemId = beforeItems.Items[0].Id;
      const firstItemPlaylistId = beforeItems.Items[0].PlaylistItemId!;

      await playlistsService.movePlaylistItem(created.Id, firstItemPlaylistId, 1);

      const afterItems = await playlistsService.getPlaylistItems(created.Id);
      expect(afterItems.Items[1].Id).toBe(firstItemId);
    } finally {
      await playlistsService.deletePlaylist(created.Id).catch(() => {});
    }
  });

  it('should fail to create playlist without authentication', async () => {
    // Create unauthenticated client
    const unauthClient = new MediaServerClient(config.serverUrl, clientInfo);
    const unauthPlaylistsService = new PlaylistsService(unauthClient);

    await expect(async () => {
      await unauthPlaylistsService.createPlaylist({
        name: 'This should fail',
        mediaType: 'Audio',
      });
    }).rejects.toThrow();
  });

  it('should fail to add items to non-existent playlist', async () => {
    const fakePlaylistId = '00000000-0000-0000-0000-000000000000';

    await expect(async () => {
      await playlistsService.addItemsToPlaylist(fakePlaylistId, [testTrackIds[0]]);
    }).rejects.toThrow();
  });

  it('should delete playlist and verify it no longer exists', async () => {
    const playlistName = `Delete Test Playlist ${Date.now()}`;

    const playlist = await playlistsService.createPlaylist({
      name: playlistName,
      mediaType: 'Audio',
      itemIds: [testTrackIds[0]],
    });

    expect(playlist.Id).toBeDefined();

    const beforeDelete = await playlistsService.getPlaylist(playlist.Id);
    expect(beforeDelete.Id).toBe(playlist.Id);

    await playlistsService.deletePlaylist(playlist.Id);

    await expect(playlistsService.getPlaylist(playlist.Id)).rejects.toThrow();
  });
});
