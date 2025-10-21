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
        if (album.AlbumArtist) {
          console.log(`  ℹ️  Artist: ${album.AlbumArtist}`);
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
        const detailed = await client.get(`/Users/${client.userId}/Items/${track.Id}`);

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
    it.skip('should search across all music items', async () => {
      // TODO: Implement searchItems method in ItemsService
      // Get first track to use as search term
      const tracks = await itemsService.getTracks({ limit: 1 });

      if (tracks.Items.length > 0) {
        const trackName = tracks.Items[0].Name;
        const searchTerm = trackName.split(' ')[0]; // Use first word

        console.log(`  ℹ️  Searching for: "${searchTerm}"`);

        // const result = await itemsService.searchItems(searchTerm, {
        //   limit: 5,
        // });

        // expect(result).toBeDefined();
        // expect(result.Items).toBeDefined();
        // expect(Array.isArray(result.Items)).toBe(true);

        // console.log(`  ℹ️  Found ${result.Items.length} results`);
      }
    });
  });

  describe('Filtering', () => {
    it('should filter by genre', async () => {
      const result = await itemsService.getAlbums({
        limit: 5,
      });

      if (result.Items.length > 0 && result.Items[0].Genres && result.Items[0].Genres!.length > 0) {
        const genre = result.Items[0].Genres![0];

        const filtered = await itemsService.getAlbums({
          limit: 10,
          filters: {
            genres: [genre],
          },
        });

        expect(filtered.Items).toBeDefined();
        console.log(`  ℹ️  Found ${filtered.Items.length} albums in genre "${genre}"`);
      }
    });

    it('should filter by year', async () => {
      const result = await itemsService.getAlbums({
        limit: 5,
      });

      if (result.Items.length > 0 && result.Items[0].ProductionYear) {
        const year = result.Items[0].ProductionYear;

        const filtered = await itemsService.getAlbums({
          limit: 10,
          filters: {
            years: [year],
          },
        });

        expect(filtered.Items).toBeDefined();
        console.log(`  ℹ️  Found ${filtered.Items.length} albums from ${year}`);
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
});
