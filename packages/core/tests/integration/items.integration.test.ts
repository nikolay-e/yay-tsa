/**
 * Integration Tests: Library Items Queries
 * Tests real library queries against a Jellyfin server
 */

import { describe, it, expect, beforeAll } from 'vitest';
import { JellyfinClient } from '../../src/api/client.js';
import { ItemsService } from '../../src/api/items.js';
import { integrationConfig } from './setup.js';
import type { MusicAlbum, MusicArtist, AudioItem } from '../../src/models/types.js';

describe.skipIf(!integrationConfig.useApiKey)('Library Items Integration', () => {
  let client: JellyfinClient;
  let itemsService: ItemsService;

  beforeAll(async () => {
    client = new JellyfinClient(integrationConfig.serverUrl, {
      name: integrationConfig.clientName,
      device: integrationConfig.deviceName,
      deviceId: integrationConfig.deviceId,
      version: '0.1.0',
    });
    await client.initWithApiKey(integrationConfig.apiKey);
    itemsService = new ItemsService(client);
  });

  describe('Albums', () => {
    it('should get list of albums', async () => {
      const result = await itemsService.getAlbums({
        limit: 10,
      });

      expect(result).toBeDefined();
      expect(result.Items).toBeDefined();
      expect(Array.isArray(result.Items)).toBe(true);
      expect(result.TotalRecordCount).toBeDefined();
      expect(typeof result.TotalRecordCount).toBe('number');

      console.log(`  ℹ️  Found ${result.TotalRecordCount} total albums`);
      console.log(`  ℹ️  Retrieved ${result.Items.length} albums`);
    });

    it('should get album details with correct structure', async () => {
      const result = await itemsService.getAlbums({ limit: 1 });

      if (result.Items.length > 0) {
        const album = result.Items[0];

        expect(album.Id).toBeDefined();
        expect(album.Name).toBeDefined();
        expect(album.Type).toBe('MusicAlbum');
        expect(album.ServerId).toBeDefined();

        console.log(`  ℹ️  Album: "${album.Name}"`);
        if (album.Artists && album.Artists.length > 0) {
          console.log(`  ℹ️  Artist: ${album.Artists[0]}`);
        }
      } else {
        console.log('  ⚠️  No albums found in library');
      }
    });

    it('should support pagination', async () => {
      const page1 = await itemsService.getAlbums({
        limit: 5,
        startIndex: 0,
      });

      const page2 = await itemsService.getAlbums({
        limit: 5,
        startIndex: 5,
      });

      expect(page1.Items).toBeDefined();
      expect(page2.Items).toBeDefined();

      if (page1.Items.length > 0 && page2.Items.length > 0) {
        expect(page1.Items[0].Id).not.toBe(page2.Items[0].Id);
      }
    });

    it('should support sorting', async () => {
      const byName = await itemsService.getAlbums({
        limit: 5,
        sortBy: 'SortName',
      });

      const byDate = await itemsService.getAlbums({
        limit: 5,
        sortBy: 'DateCreated',
      });

      expect(byName.Items).toBeDefined();
      expect(byDate.Items).toBeDefined();
    });
  });

  describe('Artists', () => {
    it('should get list of artists', async () => {
      const result = await itemsService.getArtists({
        limit: 10,
      });

      expect(result).toBeDefined();
      expect(result.Items).toBeDefined();
      expect(Array.isArray(result.Items)).toBe(true);
      expect(result.TotalRecordCount).toBeDefined();

      console.log(`  ℹ️  Found ${result.TotalRecordCount} total artists`);
      console.log(`  ℹ️  Retrieved ${result.Items.length} artists`);
    });

    it('should get artist details with correct structure', async () => {
      const result = await itemsService.getArtists({ limit: 1 });

      if (result.Items.length > 0) {
        const artist = result.Items[0];

        expect(artist.Id).toBeDefined();
        expect(artist.Name).toBeDefined();
        expect(artist.Type).toBe('MusicArtist');
        expect(artist.ServerId).toBeDefined();

        console.log(`  ℹ️  Artist: "${artist.Name}"`);
      } else {
        console.log('  ⚠️  No artists found in library');
      }
    });
  });

  describe('Tracks', () => {
    it('should get list of tracks', async () => {
      const result = await itemsService.getTracks({
        limit: 10,
      });

      expect(result).toBeDefined();
      expect(result.Items).toBeDefined();
      expect(Array.isArray(result.Items)).toBe(true);
      expect(result.TotalRecordCount).toBeDefined();

      console.log(`  ℹ️  Found ${result.TotalRecordCount} total tracks`);
      console.log(`  ℹ️  Retrieved ${result.Items.length} tracks`);
    });

    it('should get track details with correct structure', async () => {
      const result = await itemsService.getTracks({ limit: 1 });

      if (result.Items.length > 0) {
        const track = result.Items[0];

        expect(track.Id).toBeDefined();
        expect(track.Name).toBeDefined();
        expect(track.Type).toBe('Audio');
        expect(track.ServerId).toBeDefined();

        console.log(`  ℹ️  Track: "${track.Name}"`);
        if (track.Album) {
          console.log(`  ℹ️  Album: ${track.Album}`);
        }
        if (track.Artists && track.Artists.length > 0) {
          console.log(`  ℹ️  Artist: ${track.Artists[0]}`);
        }
      } else {
        console.log('  ⚠️  No tracks found in library');
      }
    });

    it('should get tracks with media sources', async () => {
      const result = await itemsService.getTracks({ limit: 1 });

      if (result.Items.length > 0) {
        const track = result.Items[0];

        // Get detailed item info
        const detailed = await client.get(`/Users/${client.getUserId()}/Items/${track.Id}`);

        expect(detailed).toBeDefined();
        expect(detailed.Id).toBe(track.Id);

        if (detailed.MediaSources && detailed.MediaSources.length > 0) {
          const mediaSource = detailed.MediaSources[0];
          console.log(`  ℹ️  Container: ${mediaSource.Container}`);
          console.log(`  ℹ️  Bitrate: ${mediaSource.Bitrate}`);
        }
      }
    });
  });

  describe('Search', () => {
    it('should search across all music items', async () => {
      // Get first track to use as search term
      const tracks = await itemsService.getTracks({ limit: 1 });

      if (tracks.Items.length > 0) {
        const trackName = tracks.Items[0].Name;
        const searchTerm = trackName.split(' ')[0]; // Use first word

        console.log(`  ℹ️  Searching for: "${searchTerm}"`);

        const result = await itemsService.search(searchTerm, {
          limit: 5,
        });

        expect(result).toBeDefined();
        expect(result.albums).toBeDefined();
        expect(result.artists).toBeDefined();
        expect(result.tracks).toBeDefined();
        expect(Array.isArray(result.albums)).toBe(true);
        expect(Array.isArray(result.artists)).toBe(true);
        expect(Array.isArray(result.tracks)).toBe(true);

        // At least one result type should have results
        const totalResults = result.albums.length + result.artists.length + result.tracks.length;
        expect(totalResults).toBeGreaterThan(0);

        console.log(
          `  ℹ️  Found: ${result.albums.length} albums, ${result.artists.length} artists, ${result.tracks.length} tracks`
        );
      } else {
        console.log('  ⊘  Skipped: No tracks available for search');
      }
    });

    it('should support searching albums with searchTerm', async () => {
      const albums = await itemsService.getAlbums({ limit: 1 });

      if (albums.Items.length > 0) {
        const albumName = albums.Items[0].Name;
        const searchTerm = albumName.split(' ')[0];

        console.log(`  ℹ️  Searching albums for: "${searchTerm}"`);

        const result = await itemsService.getAlbums({ searchTerm, limit: 5 });

        expect(result).toBeDefined();
        expect(result.Items).toBeDefined();
        expect(result.Items.length).toBeGreaterThan(0);

        console.log(`  ℹ️  Found ${result.Items.length} albums`);
      }
    });

    it('should support searching artists with searchTerm', async () => {
      const artists = await itemsService.getArtists({ limit: 1 });

      if (artists.Items.length > 0) {
        const artistName = artists.Items[0].Name;
        const searchTerm = artistName.split(' ')[0];

        console.log(`  ℹ️  Searching artists for: "${searchTerm}"`);

        const result = await itemsService.getArtists({ searchTerm, limit: 5 });

        expect(result).toBeDefined();
        expect(result.Items).toBeDefined();
        expect(result.Items.length).toBeGreaterThan(0);

        console.log(`  ℹ️  Found ${result.Items.length} artists`);
      }
    });
  });

  describe('Filtering', () => {
    it.skip('should filter by genre', async () => {
      // TODO: Add genreIds filtering support to getAlbums
      const result = await itemsService.getAlbums({
        limit: 5,
      });

      if (result.Items.length > 0 && result.Items[0].Genres && result.Items[0].Genres!.length > 0) {
        const genre = result.Items[0].Genres![0];
        console.log(`  ℹ️  Found albums with genre "${genre}"`);
        // Need genre IDs API to implement filtering
      }
    });

    it.skip('should filter by year', async () => {
      // TODO: Add year filtering support to getAlbums
      const result = await itemsService.getAlbums({
        limit: 5,
      });

      if (result.Items.length > 0 && result.Items[0].ProductionYear) {
        const year = result.Items[0].ProductionYear;
        console.log(`  ℹ️  Found albums from ${year}`);
        // Need year filtering API to implement
      }
    });
  });

  describe('Stream URLs', () => {
    it('should generate valid stream URL for track', async () => {
      const result = await itemsService.getTracks({ limit: 1 });

      if (result.Items.length > 0) {
        const track = result.Items[0];
        const streamUrl = client.getStreamUrl(track.Id);

        expect(streamUrl).toBeDefined();
        expect(streamUrl).toContain(integrationConfig.serverUrl);
        expect(streamUrl).toContain(`/Audio/${track.Id}/stream`);
        expect(streamUrl).toContain(`api_key=${integrationConfig.apiKey}`);

        console.log(`  ℹ️  Stream URL generated for: "${track.Name}"`);

        // Verify stream URL is accessible
        const response = await fetch(streamUrl, { method: 'HEAD' });
        expect(response.ok).toBe(true);

        const contentType = response.headers.get('content-type');
        console.log(`  ℹ️  Stream URL is accessible (${response.status} ${response.statusText})`);
        console.log(`  ℹ️  Content-Type: ${contentType}`);
      }
    });
  });

  describe('Get Single Item', () => {
    it('should get single album by ID', async () => {
      const albums = await itemsService.getAlbums({ limit: 1 });

      if (albums.Items.length > 0) {
        const albumId = albums.Items[0].Id;
        const album = await itemsService.getItem(albumId);

        expect(album).toBeDefined();
        expect(album.Id).toBe(albumId);
        expect(album.Type).toBe('MusicAlbum');

        console.log(`  ℹ️  Retrieved album: "${album.Name}"`);
      }
    });

    it('should get single artist by ID', async () => {
      const artists = await itemsService.getArtists({ limit: 1 });

      if (artists.Items.length > 0) {
        const artistId = artists.Items[0].Id;
        const artist = await itemsService.getItem(artistId);

        expect(artist).toBeDefined();
        expect(artist.Id).toBe(artistId);
        expect(artist.Type).toBe('MusicArtist');

        console.log(`  ℹ️  Retrieved artist: "${artist.Name}"`);
      }
    });

    it('should get single track by ID', async () => {
      const tracks = await itemsService.getTracks({ limit: 1 });

      if (tracks.Items.length > 0) {
        const trackId = tracks.Items[0].Id;
        const track = await itemsService.getItem(trackId);

        expect(track).toBeDefined();
        expect(track.Id).toBe(trackId);
        expect(track.Type).toBe('Audio');

        console.log(`  ℹ️  Retrieved track: "${track.Name}"`);
      }
    });
  });

  describe('Convenience Methods', () => {
    it('should get album tracks', async () => {
      const albums = await itemsService.getAlbums({ limit: 1 });

      if (albums.Items.length > 0) {
        const albumId = albums.Items[0].Id;
        const tracks = await itemsService.getAlbumTracks(albumId);

        expect(tracks).toBeDefined();
        expect(Array.isArray(tracks)).toBe(true);

        console.log(`  ℹ️  Album "${albums.Items[0].Name}" has ${tracks.length} tracks`);

        if (tracks.length > 0) {
          expect(tracks[0].AlbumId).toBe(albumId);
        }
      }
    });

    it('should get artist albums', async () => {
      const artists = await itemsService.getArtists({ limit: 1 });

      if (artists.Items.length > 0) {
        const artistId = artists.Items[0].Id;
        const albums = await itemsService.getArtistAlbums(artistId);

        expect(albums).toBeDefined();
        expect(Array.isArray(albums)).toBe(true);

        console.log(`  ℹ️  Artist "${artists.Items[0].Name}" has ${albums.length} albums`);

        if (albums.length > 0) {
          expect(albums[0].Type).toBe('MusicAlbum');
        }
      }
    });

    it('should get recent albums', async () => {
      const recentAlbums = await itemsService.getRecentAlbums(5);

      expect(recentAlbums).toBeDefined();
      expect(Array.isArray(recentAlbums)).toBe(true);
      expect(recentAlbums.length).toBeGreaterThan(0);
      expect(recentAlbums.length).toBeLessThanOrEqual(5);

      console.log(`  ℹ️  Found ${recentAlbums.length} recent albums`);

      if (recentAlbums.length > 0) {
        console.log(`  ℹ️  Most recent: "${recentAlbums[0].Name}"`);
      }
    });
  });

  describe('Image URLs', () => {
    it('should generate image URL for album', async () => {
      const albums = await itemsService.getAlbums({ limit: 1 });

      if (albums.Items.length > 0 && albums.Items[0].ImageTags?.Primary) {
        const album = albums.Items[0];
        const imageUrl = itemsService.getImageUrl(album.Id, 'Primary', {
          tag: album.ImageTags!.Primary,
          maxWidth: 300,
          maxHeight: 300,
        });

        expect(imageUrl).toBeDefined();
        expect(imageUrl).toContain(integrationConfig.serverUrl);
        expect(imageUrl).toContain(album.Id);
        expect(imageUrl).toContain('Primary');

        console.log(`  ℹ️  Image URL generated for album: "${album.Name}"`);
      } else {
        console.log('  ⊘  Skipped: No albums with images');
      }
    });

    it('should generate image URL for artist', async () => {
      const artists = await itemsService.getArtists({ limit: 1 });

      if (artists.Items.length > 0 && artists.Items[0].ImageTags?.Primary) {
        const artist = artists.Items[0];
        const imageUrl = itemsService.getImageUrl(artist.Id, 'Primary');

        expect(imageUrl).toBeDefined();
        expect(imageUrl).toContain(integrationConfig.serverUrl);
        expect(imageUrl).toContain(artist.Id);

        console.log(`  ℹ️  Image URL generated for artist: "${artist.Name}"`);
      } else {
        console.log('  ⊘  Skipped: No artists with images');
      }
    });
  });
});
