/**
 * Test Data Factory
 * Discovers and prepares predictable test fixtures from existing Media Server library
 * Since Media Server doesn't support creating artists/albums via API, we discover existing data
 */

import { MediaServerClient } from '../../../src/api/api.client.js';
import { AuthService } from '../../../src/api/auth.service.js';
import { ItemsService } from '../../../src/api/items.service.js';
import { PlaylistsService } from '../../../src/api/playlists.service.js';
import type { AudioItem, MusicAlbum, MusicArtist } from '../../../src/index.js';
import { TestConfig } from '../setup.js';

export interface TestFixtures {
  // Discovered library data (read-only, not modified by tests)
  artists: MusicArtist[];
  albums: MusicAlbum[];
  tracks: AudioItem[];

  // Created test data (modified/cleaned up by tests)
  playlists: { id: string; name: string }[];

  // Services
  client: MediaServerClient;
  authService: AuthService;
  itemsService: ItemsService;
  playlistsService: PlaylistsService;

  // Test configuration (needed for cleanup)
  config: TestConfig;
}

export class TestDataFactory {
  private client: MediaServerClient;
  private authService: AuthService;
  private itemsService: ItemsService;
  private playlistsService: PlaylistsService;

  private createdPlaylistIds: string[] = [];

  constructor(private config: TestConfig) {
    this.client = new MediaServerClient(config.serverUrl, {
      clientName: 'Media Server Client Integration Tests',
      deviceName: 'Integration Test Runner',
      version: '0.1.0',
    });

    this.authService = new AuthService(this.client);
    this.itemsService = new ItemsService(this.client);
    this.playlistsService = new PlaylistsService(this.client);
  }

  /**
   * Setup test fixtures by discovering existing library data
   * Given: Media Server with existing music library
   * When: Tests need predictable data
   * Then: Return first N artists/albums/tracks as fixtures
   */
  async setup(): Promise<TestFixtures> {
    // Authenticate first
    await this.authService.login(this.config.username, this.config.password);

    // Discover existing library data (minimum required: 3 artists, 5 albums, 50 tracks)
    const [artistsResult, albumsResult, tracksResult] = await Promise.all([
      this.itemsService.getArtists({ limit: 5 }),
      this.itemsService.getAlbums({ limit: 10 }),
      this.itemsService.getTracks({ limit: 100 }),
    ]);

    // Validate library has enough data for testing
    this.validateLibraryData(artistsResult.Items, albumsResult.Items, tracksResult.Items);

    // Return fixtures
    return {
      artists: artistsResult.Items.slice(0, 3),
      albums: albumsResult.Items.slice(0, 5),
      tracks: tracksResult.Items.slice(0, 50),
      playlists: [],
      client: this.client,
      authService: this.authService,
      itemsService: this.itemsService,
      playlistsService: this.playlistsService,
      config: this.config,
    };
  }

  /**
   * Validate library has sufficient data for testing
   */
  private validateLibraryData(
    artists: MusicArtist[],
    albums: MusicAlbum[],
    tracks: AudioItem[]
  ): void {
    const errors: string[] = [];

    if (artists.length < 3) {
      errors.push(`Insufficient artists: found ${artists.length}, need at least 3`);
    }

    if (albums.length < 5) {
      errors.push(`Insufficient albums: found ${albums.length}, need at least 5`);
    }

    if (tracks.length < 50) {
      errors.push(`Insufficient tracks: found ${tracks.length}, need at least 50`);
    }

    if (errors.length > 0) {
      throw new Error(
        `Library validation failed:\n${errors.join('\n')}\n\n` +
          `Please add more music to your Media Server test library.`
      );
    }
  }

  /**
   * Create a test playlist and track it for cleanup
   */
  async createTestPlaylist(
    name: string,
    itemIds?: string[]
  ): Promise<{ id: string; name: string }> {
    const timestamp = Date.now();
    const playlistName = `[INTEGRATION-TEST] ${name} ${timestamp}`;

    const playlist = await this.playlistsService.createPlaylist({
      name: playlistName,
      mediaType: 'Audio',
      itemIds: itemIds || [],
    });

    this.createdPlaylistIds.push(playlist.Id);

    return {
      id: playlist.Id,
      name: playlistName,
    };
  }

  /**
   * Cleanup all test data created during test run
   * Given: Tests have created playlists
   * When: Tests complete
   * Then: Remove all test-created data to restore original state
   */
  async cleanup(): Promise<void> {
    const errors: Error[] = [];

    // Delete playlists
    for (const playlistId of this.createdPlaylistIds) {
      try {
        await this.playlistsService.deletePlaylist(playlistId);
      } catch (error) {
        errors.push(
          new Error(`Failed to delete playlist ${playlistId}: ${(error as Error).message}`)
        );
      }
    }

    // Report cleanup errors (but don't fail tests)
    if (errors.length > 0) {
      console.warn(`\n⚠️  Cleanup warnings (${errors.length} failures):`);
      errors.forEach(err => console.warn(`  - ${err.message}`));
    }

    // Reset tracking
    this.createdPlaylistIds = [];
  }

  /**
   * Get album tracks for testing queue operations
   */
  async getAlbumTracks(albumId: string): Promise<AudioItem[]> {
    return this.itemsService.getAlbumTracks(albumId);
  }
}
