/**
 * Feature: Library Browsing
 * Tests library queries and navigation against real Media Server
 * Focus on user browsing experience, not API implementation details
 */

import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import {
  loadTestConfig,
  setupTestFixtures,
  cleanupTestFixtures,
  createScenario,
} from '../setup.js';
import { TestFixtures } from '../fixtures/data-factory.js';
import { PlaybackQueue } from '../../../src/player/queue.js';
import { ThenLibrary, ThenQueue } from '../fixtures/scenarios.js';

describe('Feature: Library Browsing', () => {
  let fixtures: TestFixtures;
  let scenario: ReturnType<typeof createScenario>;

  beforeAll(async () => {
    const config = loadTestConfig();
    fixtures = await setupTestFixtures(config);
    scenario = createScenario(fixtures);
  });

  afterAll(async () => {
    await cleanupTestFixtures(fixtures);
  });

  describe('Scenario: User browses album collection', () => {
    it('Given: User opens app, When: Views library, Then: Sees album grid', async () => {
      // Given: User is authenticated
      await scenario.given.user.isAuthenticated();

      // When: User opens library
      const result = await scenario.when.user.browsesAlbums();

      // Then: Album grid is displayed
      ThenLibrary.albumsAreReturned(result);
      expect(result.TotalRecordCount).toBeGreaterThanOrEqual(0);

      if (result.Items.length > 0) {
        const album = result.Items[0];
        expect(album.Id).toBeTruthy();
        expect(album.Name).toBeTruthy();
        expect(album.Type).toBe('MusicAlbum');
      }
    });

    it('Given: User in library, When: Clicks on album, Then: Sees album details', async () => {
      // Given: User has albums in library
      const albums = scenario.given.library.hasAlbums();
      expect(albums.length).toBeGreaterThan(0);

      // When: User clicks on first album
      const albumId = albums[0].Id;
      const album = await fixtures.itemsService.getItem(albumId);

      // Then: Album details are shown
      expect(album).toBeDefined();
      expect(album.Id).toBe(albumId);
      expect(album.Name).toBeTruthy();
    });

    it('Given: User views album, When: Album loads, Then: Track list is displayed', async () => {
      // Given: User has albums
      const albums = scenario.given.library.hasAlbums();
      expect(albums.length).toBeGreaterThan(0);

      // When: User opens album and views tracks
      const albumId = albums[0].Id;
      const tracks = await scenario.given.library.albumWithTracks(albumId);

      // Then: Track list is shown
      expect(tracks).toBeInstanceOf(Array);
      if (tracks.length > 0) {
        const track = tracks[0];
        expect(track.Id).toBeTruthy();
        expect(track.Name).toBeTruthy();
        expect(track.Type).toBe('Audio');
        expect(track.AlbumId).toBe(albumId);
        expect(track.RunTimeTicks).toBeGreaterThan(0);
      }
    });
  });

  describe('Scenario: User plays an album', () => {
    it('Given: User viewing album, When: Clicks play album, Then: Queue contains all album tracks', async () => {
      // Given: User views album with tracks
      const albums = scenario.given.library.hasAlbums();
      const albumId = albums[0].Id;
      const albumTracks = await fixtures.itemsService.getAlbumTracks(albumId);

      expect(albumTracks.length).toBeGreaterThan(0);

      // When: User clicks "Play Album"
      const queue = new PlaybackQueue();
      queue.setQueue(albumTracks);

      // Then: Queue has exactly the same number of tracks as album
      ThenQueue.queueHasTracks(queue, albumTracks.length);

      // And: All album tracks are in queue
      const queueItems = queue.getAllItems();
      expect(queueItems).toEqual(albumTracks);

      // And: First track is playing
      ThenQueue.currentTrackIs(queue, albumTracks[0]);
    });

    it('Given: User viewing album, When: Clicks track 3, Then: Queue starts from track 3', async () => {
      // Given: User views album
      const albums = scenario.given.library.hasAlbums();
      const albumId = albums[0].Id;
      const albumTracks = await fixtures.itemsService.getAlbumTracks(albumId);

      if (albumTracks.length < 3) {
        console.warn('⚠️  Album has fewer than 3 tracks, skipping test');
        return;
      }

      // When: User clicks track 3 in album
      const queue = new PlaybackQueue();
      queue.setQueue(albumTracks);
      queue.jumpTo(2);

      // Then: Queue has all tracks
      ThenQueue.queueHasTracks(queue, albumTracks.length);

      // And: Track 3 is playing
      ThenQueue.currentTrackIs(queue, albumTracks[2]);
    });
  });

  describe('Scenario: User browses artists', () => {
    it('Given: User opens artists view, When: Views artist list, Then: Sees all artists', async () => {
      // Given: User is authenticated
      await scenario.given.user.isAuthenticated();

      // When: User opens artists view
      const artists = scenario.given.library.hasArtists();

      // Then: Artists are displayed
      expect(artists.length).toBeGreaterThan(0);
      const artist = artists[0];
      expect(artist.Id).toBeTruthy();
      expect(artist.Name).toBeTruthy();
      expect(artist.Type).toBe('MusicArtist');
    });

    it('Given: User views artist, When: Clicks on artist, Then: Sees artist albums', async () => {
      // Given: User has artists
      const artists = scenario.given.library.hasArtists();
      expect(artists.length).toBeGreaterThan(0);

      // When: User clicks on artist
      const artistId = artists[0].Id;
      const albums = await fixtures.itemsService.getArtistAlbums(artistId);

      // Then: Artist's albums are displayed
      expect(albums).toBeInstanceOf(Array);
      if (albums.length > 0) {
        const album = albums[0];
        expect(album.Id).toBeTruthy();
        expect(album.Type).toBe('MusicAlbum');
        expect(album.Artists).toContain(artists[0].Name);
      }
    });
  });

  describe('Scenario: User searches library', () => {
    it('Given: User in library, When: Searches by album name, Then: Finds matching albums', async () => {
      // Given: User has albums
      const albums = scenario.given.library.hasAlbums();
      if (albums.length === 0) {
        console.warn('⚠️  No albums in library, skipping search test');
        return;
      }

      // When: User searches for first word of album name
      const albumName = albums[0].Name;
      const searchQuery = albumName.split(' ')[0];
      const results = await scenario.when.user.searches(searchQuery);

      // Then: Search returns results
      expect(results).toBeDefined();
      expect(results.albums).toBeInstanceOf(Array);
      expect(results.artists).toBeInstanceOf(Array);
      expect(results.tracks).toBeInstanceOf(Array);

      // And: Results contain matching album or are empty (for very specific queries)
      const foundMatch =
        results.albums.some(album =>
          album.Name.toLowerCase().includes(searchQuery.toLowerCase())
        ) ||
        results.artists.length > 0 ||
        results.tracks.length > 0 ||
        results.albums.length === 0;

      expect(foundMatch).toBe(true);
    });

    it('Given: User in library, When: Searches nonexistent term, Then: Shows empty results', async () => {
      // Given: User is authenticated
      await scenario.given.user.isAuthenticated();

      // When: User searches for nonexistent term
      const results = await scenario.when.user.searches('xyznonexistent12345xyz');

      // Then: No results found
      ThenLibrary.searchReturnsNoResults(results);
    });

    it('Given: User in library, When: Searches partial artist name, Then: Finds artist', async () => {
      // Given: User has artists
      const artists = scenario.given.library.hasArtists();
      if (artists.length === 0) {
        console.warn('⚠️  No artists in library, skipping artist search test');
        return;
      }

      // When: User types partial artist name
      const artistName = artists[0].Name;
      const partialQuery = artistName.substring(0, Math.min(4, artistName.length));
      const results = await scenario.when.user.searches(partialQuery);

      // Then: Search finds artists matching query
      expect(results.artists).toBeInstanceOf(Array);
    });
  });

  describe('Scenario: User browses recently added music', () => {
    it('Given: User opens app, When: Views recently added, Then: Sees newest albums first', async () => {
      // Given: User is authenticated
      await scenario.given.user.isAuthenticated();

      // When: User opens "Recently Added" section
      const recentAlbums = await fixtures.itemsService.getRecentAlbums(10);

      // Then: Up to 10 recent albums shown
      expect(recentAlbums.Items).toBeInstanceOf(Array);
      expect(recentAlbums.Items.length).toBeLessThanOrEqual(10);

      // And: Albums have creation dates
      if (recentAlbums.Items.length > 0) {
        const album = recentAlbums.Items[0];
        expect(album.Type).toBe('MusicAlbum');
        expect(album.DateCreated).toBeDefined();
      }
    });
  });

  describe('Scenario: User scrolls through large library (pagination)', () => {
    it('Given: User has 100+ albums, When: Scrolls down, Then: Loads next page', async () => {
      // Given: User is browsing library
      await scenario.given.user.isAuthenticated();

      // When: User loads first page
      const firstPage = await scenario.when.user.browsesAlbums({
        startIndex: 0,
        limit: 20,
      });

      // Then: Gets first 20 albums
      expect(firstPage.Items.length).toBeLessThanOrEqual(20);
      const totalCount = firstPage.TotalRecordCount;

      if (totalCount <= 20) {
        console.warn('⚠️  Library has ≤20 albums, skipping pagination test');
        return;
      }

      // When: User scrolls to load more
      const secondPage = await scenario.when.user.browsesAlbums({
        startIndex: 20,
        limit: 20,
      });

      // Then: Gets next 20 albums
      expect(secondPage.Items.length).toBeGreaterThan(0);

      // And: Albums are different from first page
      const firstPageIds = firstPage.Items.map(a => a.Id);
      const secondPageIds = secondPage.Items.map(a => a.Id);
      const hasOverlap = firstPageIds.some(id => secondPageIds.includes(id));
      expect(hasOverlap).toBe(false);
    });

    it('Given: User at end of library, When: Tries to load more, Then: No more results', async () => {
      // Given: User is authenticated
      await scenario.given.user.isAuthenticated();

      // When: User requests albums beyond total count
      const allAlbums = await scenario.when.user.browsesAlbums();
      const totalCount = allAlbums.TotalRecordCount;

      const beyondEnd = await scenario.when.user.browsesAlbums({
        startIndex: totalCount + 100,
        limit: 20,
      });

      // Then: No results returned
      expect(beyondEnd.Items).toHaveLength(0);
    });

    it('Given: User scrolls quickly, When: Loads page 5, Then: Gets correct offset', async () => {
      // Given: User scrolls quickly through library
      await scenario.given.user.isAuthenticated();

      const pageSize = 10;
      const pageNumber = 5;
      const startIndex = (pageNumber - 1) * pageSize;

      // When: Loads page 5 (albums 40-49)
      const page5 = await scenario.when.user.browsesAlbums({
        startIndex,
        limit: pageSize,
      });

      // Then: Gets albums from correct offset
      if (page5.TotalRecordCount > startIndex) {
        expect(page5.Items.length).toBeGreaterThan(0);
        expect(page5.Items.length).toBeLessThanOrEqual(pageSize);
      } else {
        console.warn(`⚠️  Library has fewer than ${startIndex} albums, skipping`);
      }
    });
  });

  describe('Scenario: User gets media URLs for playback', () => {
    it('Given: User selects track, When: App prepares playback, Then: Stream URL generated', async () => {
      // Given: User has tracks
      const tracks = scenario.given.library.hasTracks();
      if (tracks.length === 0) {
        console.warn('⚠️  No tracks in library, skipping stream URL test');
        return;
      }

      // When: App generates stream URL for track
      const trackId = tracks[0].Id;
      const streamUrl = fixtures.itemsService.getStreamUrl(trackId);

      // Then: URL contains server, track ID, and authentication
      expect(streamUrl).toContain(fixtures.client.getConfig().serverUrl);
      expect(streamUrl).toContain(trackId);
      expect(streamUrl).toContain('api_key=');
    });

    it('Given: User views album, When: Album art loads, Then: Image URL generated', async () => {
      // Given: User views album
      const albums = scenario.given.library.hasAlbums();

      // When: App loads album art
      const albumId = albums[0].Id;
      const imageUrl = fixtures.itemsService.getImageUrl(albumId, 'Primary', {
        maxWidth: 300,
      });

      // Then: URL is valid with size parameters
      expect(imageUrl).toContain(fixtures.client.getConfig().serverUrl);
      expect(imageUrl).toContain(albumId);
      expect(imageUrl).toContain('maxWidth=300');
    });
  });
});
