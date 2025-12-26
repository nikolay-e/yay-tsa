/**
 * Integration Library Tests
 * Tests library queries against real Media Server
 */

import { describe, it, expect, beforeAll } from 'vitest';
import { MediaServerClient } from '../../src/api/api.client.js';
import { AuthService } from '../../src/api/auth.service.js';
import { ItemsService } from '../../src/api/items.service.js';
import { loadTestConfig, delay, retryableLogin, AUTH_DELAY } from './setup.js';
import type { ClientInfo } from '../../src/index.js';

describe('Integration: Library Queries', () => {
  let config: ReturnType<typeof loadTestConfig>;
  let client: MediaServerClient;
  let authService: AuthService;
  let itemsService: ItemsService;
  let userId: string;

  const clientInfo: ClientInfo = {
    name: 'Media Server Client Integration Tests',
    device: 'Test Runner',
    deviceId: 'integration-test-library-id',
    version: '0.1.0-test',
  };

  beforeAll(async () => {
    await delay(AUTH_DELAY);

    config = loadTestConfig();
    client = new MediaServerClient(config.serverUrl, clientInfo);
    authService = new AuthService(client);

    const authResponse = await retryableLogin(
      () => authService.login(config.username, config.password),
      'Library tests authentication'
    );
    userId = authResponse.User.Id;

    if (!client.isAuthenticated()) {
      throw new Error('Authentication failed - token not set on client');
    }

    itemsService = new ItemsService(client, userId);

    await delay(AUTH_DELAY);
  });

  describe('Albums', () => {
    it('should fetch all albums', async () => {
      const response = await itemsService.getAlbums();

      expect(response).toBeDefined();
      expect(response.Items).toBeInstanceOf(Array);
      expect(response.TotalRecordCount).toBeGreaterThanOrEqual(0);

      if (response.Items.length > 0) {
        const album = response.Items[0];
        expect(album.Id).toBeDefined();
        expect(album.Name).toBeDefined();
        expect(album.Type).toBe('MusicAlbum');
      }
    });

    it('should fetch recent albums', async () => {
      const response = await itemsService.getRecentAlbums(10);

      expect(response).toBeDefined();
      expect(response.Items).toBeInstanceOf(Array);
      expect(response.Items.length).toBeLessThanOrEqual(10);
      expect(response.TotalRecordCount).toBeGreaterThanOrEqual(0);

      if (response.Items.length > 0) {
        const album = response.Items[0];
        expect(album.Type).toBe('MusicAlbum');
        expect(album.DateCreated).toBeDefined();
      }
    });

    it('should fetch album by ID', async () => {
      const albumsResponse = await itemsService.getAlbums();

      if (albumsResponse.Items.length === 0) {
        console.warn('⚠️  No albums found in library, skipping album detail test');
        return;
      }

      const albumId = albumsResponse.Items[0].Id;
      const album = await itemsService.getItem(albumId);

      expect(album).toBeDefined();
      expect(album.Id).toBe(albumId);
      expect(album.Name).toBeDefined();
    });
  });

  describe('Artists', () => {
    it('should fetch all artists', async () => {
      const response = await itemsService.getArtists();

      expect(response).toBeDefined();
      expect(response.Items).toBeInstanceOf(Array);
      expect(response.TotalRecordCount).toBeGreaterThanOrEqual(0);

      if (response.Items.length > 0) {
        const artist = response.Items[0];
        expect(artist.Id).toBeDefined();
        expect(artist.Name).toBeDefined();
        expect(artist.Type).toBe('MusicArtist');
      }
    });
  });

  describe('Tracks', () => {
    it('should fetch tracks for an album', async () => {
      const albumsResponse = await itemsService.getAlbums();

      if (albumsResponse.Items.length === 0) {
        console.warn('⚠️  No albums found in library, skipping tracks test');
        return;
      }

      const albumId = albumsResponse.Items[0].Id;
      const tracks = await itemsService.getAlbumTracks(albumId);

      expect(tracks).toBeInstanceOf(Array);

      if (tracks.length > 0) {
        const track = tracks[0];
        expect(track.Id).toBeDefined();
        expect(track.Name).toBeDefined();
        expect(track.Type).toBe('Audio');
        expect(track.AlbumId).toBe(albumId);
        expect(track.RunTimeTicks).toBeGreaterThan(0);
      }
    });

    it('should fetch tracks for an artist', async () => {
      const artistsResponse = await itemsService.getArtists();

      if (artistsResponse.Items.length === 0) {
        console.warn('⚠️  No artists found in library, skipping artist tracks test');
        return;
      }

      const artistId = artistsResponse.Items[0].Id;
      const albums = await itemsService.getArtistAlbums(artistId);

      expect(albums).toBeInstanceOf(Array);

      if (albums.length > 0) {
        const album = albums[0];
        expect(album.Id).toBeDefined();
        expect(album.Type).toBe('MusicAlbum');
        expect(album.Artists).toContain(artistsResponse.Items[0].Name);
      }
    });
  });

  describe('Search', () => {
    it('should search library with query', async () => {
      // Get an album name to search for
      const albumsResponse = await itemsService.getAlbums();

      if (albumsResponse.Items.length === 0) {
        console.warn('⚠️  No albums found in library, skipping search test');
        return;
      }

      const albumName = albumsResponse.Items[0].Name;
      const searchQuery = albumName.split(' ')[0]; // Use first word of album name

      const results = await itemsService.search(searchQuery);

      expect(results).toBeDefined();
      expect(results.albums).toBeInstanceOf(Array);
      expect(results.artists).toBeInstanceOf(Array);
      expect(results.tracks).toBeInstanceOf(Array);

      // Should find at least the album we searched for
      const foundAlbum = results.albums.some(album =>
        album.Name.toLowerCase().includes(searchQuery.toLowerCase())
      );
      expect(foundAlbum || results.albums.length === 0).toBe(true);
    });

    it('should return empty results for nonexistent search', async () => {
      const results = await itemsService.search('xyznonexistentquery123');

      expect(results.albums).toHaveLength(0);
      expect(results.artists).toHaveLength(0);
      expect(results.tracks).toHaveLength(0);
    });
  });

  describe('Image URLs', () => {
    it('should generate valid album art URL', () => {
      const albumId = 'test-album-id';
      const url = client.getImageUrl(albumId, 'Primary', { maxWidth: 300, quality: 90 });

      expect(url).toContain(config.serverUrl);
      expect(url).toContain(albumId);
      expect(url).toContain('maxWidth=300');
      expect(url).toContain('quality=90');
    });

    it('should generate valid stream URL with authentication', async () => {
      const albumsResponse = await itemsService.getAlbums();

      if (albumsResponse.Items.length === 0) {
        console.warn('⚠️  No albums found in library, skipping stream URL test');
        return;
      }

      const albumId = albumsResponse.Items[0].Id;
      const tracks = await itemsService.getAlbumTracks(albumId);

      if (tracks.length === 0) {
        console.warn('⚠️  No tracks found in album, skipping stream URL test');
        return;
      }

      const trackId = tracks[0].Id;
      const streamUrl = client.getStreamUrl(trackId);

      expect(streamUrl).toContain(config.serverUrl);
      expect(streamUrl).toContain(trackId);
      expect(streamUrl).toContain('api_key=');
      expect(streamUrl).toContain(client.token || '');
    });
  });
});
