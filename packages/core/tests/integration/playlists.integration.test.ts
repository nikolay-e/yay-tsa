/**
 * Integration Playlists Tests
 * Tests playlist management against real Media Server
 */

import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import { MediaServerClient } from '../../src/api/client.js';
import { AuthService } from '../../src/api/auth.js';
import { ItemsService } from '../../src/api/items.js';
import { PlaylistsService } from '../../src/api/playlists.js';
import { loadTestConfig, delay, retryableLogin, AUTH_DELAY } from './setup.js';
import type { ClientInfo } from '../../src/models/types.js';

describe('Integration: Playlists Management', () => {
  let config: ReturnType<typeof loadTestConfig>;
  let client: MediaServerClient;
  let authService: AuthService;
  let itemsService: ItemsService;
  let playlistsService: PlaylistsService;
  let userId: string;
  let testTrackIds: string[] = [];
  let testPlaylistId: string | null = null;

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

    const authResponse = await retryableLogin(
      () => authService.login(config.username, config.password),
      'Playlists tests authentication'
    );
    userId = authResponse.User.Id;

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
    // Clean up: delete test playlist if it was created
    if (testPlaylistId && playlistsService) {
      try {
        await playlistsService.deletePlaylist(testPlaylistId);
      } catch (error) {
        // Ignore cleanup errors
      }
    }

    // Add delay after cleanup
    await delay(AUTH_DELAY);
  });

  it('should create an empty playlist', async () => {
    const playlistName = `Test Playlist ${Date.now()}`;

    const playlist = await playlistsService.createPlaylist({
      name: playlistName,
      mediaType: 'Audio',
    });

    expect(playlist).toBeDefined();
    expect(playlist.Id).toBeDefined();
    expect(playlist.Name).toBe(playlistName);
    expect(playlist.Type).toBe('Playlist');

    // Store for cleanup
    testPlaylistId = playlist.Id;
  });

  it('should get playlist details', async () => {
    expect(testPlaylistId).not.toBeNull();

    const playlist = await playlistsService.getPlaylist(testPlaylistId!);

    expect(playlist).toBeDefined();
    expect(playlist.Id).toBe(testPlaylistId);
    expect(playlist.Type).toBe('Playlist');
    expect(playlist.Name).toBeDefined();
  });

  it('should add items to playlist', async () => {
    expect(testPlaylistId).not.toBeNull();
    expect(testTrackIds.length).toBeGreaterThanOrEqual(2);

    // Add first two tracks
    const itemsToAdd = testTrackIds.slice(0, 2);
    await playlistsService.addItemsToPlaylist(testPlaylistId!, itemsToAdd);

    // Verify items were added by getting playlist items
    const items = await playlistsService.getPlaylistItems(testPlaylistId!);

    expect(items).toBeDefined();
    expect(items.Items.length).toBe(2);
    expect(items.Items[0].Id).toBeDefined();
    expect(items.Items[1].Id).toBeDefined();
  });

  it('should get playlist items', async () => {
    expect(testPlaylistId).not.toBeNull();

    const items = await playlistsService.getPlaylistItems(testPlaylistId!);

    expect(items).toBeDefined();
    expect(items.Items).toBeInstanceOf(Array);
    expect(items.Items.length).toBeGreaterThan(0);
    expect(items.TotalRecordCount).toBeGreaterThan(0);

    // Verify items have proper structure
    const firstItem = items.Items[0];
    expect(firstItem.Id).toBeDefined();
    expect(firstItem.Type).toBe('Audio');
    expect(firstItem.Name).toBeDefined();
  });

  it('should add more items to existing playlist', async () => {
    expect(testPlaylistId).not.toBeNull();
    expect(testTrackIds.length).toBeGreaterThanOrEqual(3);

    // Get current count
    const beforeItems = await playlistsService.getPlaylistItems(testPlaylistId!);
    const beforeCount = beforeItems.Items.length;

    // Add third track
    await playlistsService.addItemsToPlaylist(testPlaylistId!, [testTrackIds[2]]);

    // Verify count increased
    const afterItems = await playlistsService.getPlaylistItems(testPlaylistId!);
    expect(afterItems.Items.length).toBe(beforeCount + 1);
  });

  it('should remove items from playlist', async () => {
    expect(testPlaylistId).not.toBeNull();

    // Get current items
    const beforeItems = await playlistsService.getPlaylistItems(testPlaylistId!);
    expect(beforeItems.Items.length).toBeGreaterThan(0);

    // Get the playlist entry ID of the first item (not the original track ID!)
    const entryIdToRemove = beforeItems.Items[0].PlaylistItemId!;
    const beforeCount = beforeItems.Items.length;

    // Remove first item
    await playlistsService.removeItemsFromPlaylist(testPlaylistId!, [entryIdToRemove]);

    // Verify count decreased
    const afterItems = await playlistsService.getPlaylistItems(testPlaylistId!);
    expect(afterItems.Items.length).toBe(beforeCount - 1);
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
    expect(testPlaylistId).not.toBeNull();

    // Get current items
    const beforeItems = await playlistsService.getPlaylistItems(testPlaylistId!);
    expect(beforeItems.Items.length).toBeGreaterThanOrEqual(2);

    const firstItemId = beforeItems.Items[0].Id;
    const firstItemPlaylistId = beforeItems.Items[0].PlaylistItemId!;

    // Move first item to index 1 (swap first two items)
    await playlistsService.movePlaylistItem(testPlaylistId!, firstItemPlaylistId, 1);

    // Verify order changed
    const afterItems = await playlistsService.getPlaylistItems(testPlaylistId!);
    expect(afterItems.Items[1].Id).toBe(firstItemId);
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
});
