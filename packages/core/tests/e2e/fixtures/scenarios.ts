/**
 * BDD Scenario Helpers
 * Reusable Given-When-Then scenario builders for readable behavior-driven tests
 */

import { expect } from 'vitest';
import { TestFixtures } from './data-factory.js';
import { AudioItem, MusicAlbum, MusicArtist } from '../../../src/models/types.js';

/**
 * Given: User authentication scenarios
 */
export class GivenUser {
  constructor(private fixtures: TestFixtures) {}

  /**
   * Given: User is authenticated
   */
  async isAuthenticated(): Promise<void> {
    expect(this.fixtures.client.isAuthenticated()).toBe(true);
  }

  /**
   * Given: User is not authenticated (fresh client)
   */
  isNotAuthenticated(): void {
    expect(this.fixtures.client.isAuthenticated()).toBe(false);
  }
}

/**
 * Given: Library state scenarios
 */
export class GivenLibrary {
  constructor(private fixtures: TestFixtures) {}

  /**
   * Given: Library has albums
   */
  hasAlbums(): MusicAlbum[] {
    expect(this.fixtures.albums.length).toBeGreaterThan(0);
    return this.fixtures.albums;
  }

  /**
   * Given: Library has artists
   */
  hasArtists(): MusicArtist[] {
    expect(this.fixtures.artists.length).toBeGreaterThan(0);
    return this.fixtures.artists;
  }

  /**
   * Given: Library has tracks
   */
  hasTracks(): AudioItem[] {
    expect(this.fixtures.tracks.length).toBeGreaterThan(0);
    return this.fixtures.tracks;
  }

  /**
   * Given: Specific album with tracks
   */
  async albumWithTracks(albumId: string): Promise<AudioItem[]> {
    const tracks = await this.fixtures.itemsService.getAlbumTracks(albumId);
    expect(tracks.length).toBeGreaterThan(0);
    return tracks;
  }
}

/**
 * Given: Playback queue scenarios
 */
export class GivenQueue {
  /**
   * Given: Empty queue
   */
  static isEmpty(queue: any): void {
    expect(queue.isEmpty()).toBe(true);
    expect(queue.getCurrentItem()).toBeNull();
    expect(queue.hasNext()).toBe(false);
    expect(queue.hasPrevious()).toBe(false);
  }

  /**
   * Given: Queue with N tracks
   */
  static hasTracks(queue: any, tracks: AudioItem[]): void {
    queue.setQueue(tracks);
    expect(queue.getAllItems()).toHaveLength(tracks.length);
    expect(queue.isEmpty()).toBe(false);
  }

  /**
   * Given: Queue is playing track at index
   */
  static isPlayingTrackAt(queue: any, index: number, expectedTrack: AudioItem): void {
    const currentTrack = queue.jumpTo(index);
    expect(currentTrack).toEqual(expectedTrack);
    expect(queue.getCurrentItem()).toEqual(expectedTrack);
  }

  /**
   * Given: Shuffle mode is enabled
   */
  static shuffleEnabled(queue: any): void {
    queue.toggleShuffleMode();
    expect(queue.getShuffleMode()).toBe('on');
  }

  /**
   * Given: Repeat mode is set
   */
  static repeatModeSet(queue: any, mode: 'off' | 'all' | 'one'): void {
    // Toggle until desired mode
    while (queue.getRepeatMode() !== mode) {
      queue.toggleRepeatMode();
    }
    expect(queue.getRepeatMode()).toBe(mode);
  }
}

/**
 * When: User actions
 */
export class WhenUser {
  constructor(private fixtures: TestFixtures) {}

  /**
   * When: User logs in
   */
  async logsIn(username: string, password: string): Promise<any> {
    return this.fixtures.authService.login(username, password);
  }

  /**
   * When: User searches library
   */
  async searches(searchTerm: string): Promise<any> {
    return this.fixtures.itemsService.search(searchTerm);
  }

  /**
   * When: User marks item as favorite
   */
  async marksAsFavorite(itemId: string): Promise<void> {
    return this.fixtures.favoritesService.markFavorite(itemId);
  }

  /**
   * When: User unmarks item as favorite
   */
  async unmarksAsFavorite(itemId: string): Promise<void> {
    return this.fixtures.favoritesService.unmarkFavorite(itemId);
  }

  /**
   * When: User creates playlist
   */
  async createsPlaylist(name: string, itemIds?: string[]): Promise<any> {
    return this.fixtures.playlistsService.createPlaylist({
      name,
      mediaType: 'Audio',
      itemIds: itemIds || [],
    });
  }

  /**
   * When: User adds tracks to playlist
   */
  async addsTracksToPlaylist(playlistId: string, trackIds: string[]): Promise<void> {
    return this.fixtures.playlistsService.addItemsToPlaylist(playlistId, trackIds);
  }

  /**
   * When: User removes tracks from playlist
   */
  async removesTracksFromPlaylist(playlistId: string, entryIds: string[]): Promise<void> {
    return this.fixtures.playlistsService.removeItemsFromPlaylist(playlistId, entryIds);
  }

  /**
   * When: User browses albums with pagination
   */
  async browsesAlbums(options?: {
    startIndex?: number;
    limit?: number;
    searchTerm?: string;
  }): Promise<any> {
    return this.fixtures.itemsService.getAlbums(options);
  }

  /**
   * When: User browses favorite albums
   */
  async browsesFavoriteAlbums(): Promise<any> {
    return this.fixtures.favoritesService.getFavoriteAlbums();
  }

  /**
   * When: User browses favorite artists
   */
  async browsesFavoriteArtists(): Promise<any> {
    return this.fixtures.favoritesService.getFavoriteArtists();
  }
}

/**
 * When: Queue actions
 */
export class WhenQueue {
  /**
   * When: User adds tracks to queue
   */
  static addsTracks(queue: any, tracks: AudioItem | AudioItem[]): void {
    if (Array.isArray(tracks)) {
      tracks.forEach(track => queue.addToQueue(track));
    } else {
      queue.addToQueue(tracks);
    }
  }

  /**
   * When: User skips to next track
   */
  static skipsToNext(queue: any): AudioItem | null {
    return queue.next();
  }

  /**
   * When: User goes to previous track
   */
  static goesToPrevious(queue: any): AudioItem | null {
    return queue.previous();
  }

  /**
   * When: User jumps to track at index
   */
  static jumpsToTrack(queue: any, index: number): AudioItem | null {
    return queue.jumpTo(index);
  }

  /**
   * When: User removes track from queue
   */
  static removesTrack(queue: any, index: number): void {
    queue.removeAt(index);
  }

  /**
   * When: User enables shuffle
   */
  static enablesShuffle(queue: any): void {
    queue.toggleShuffleMode();
  }

  /**
   * When: User disables shuffle
   */
  static disablesShuffle(queue: any): void {
    if (queue.getShuffleMode() === 'on') {
      queue.toggleShuffleMode();
    }
  }

  /**
   * When: User changes repeat mode
   */
  static changesRepeatMode(queue: any): void {
    queue.toggleRepeatMode();
  }

  /**
   * When: User clears queue
   */
  static clearsQueue(queue: any): void {
    queue.clear();
  }
}

/**
 * Then: Authentication expectations
 */
export class ThenAuth {
  /**
   * Then: User is authenticated
   */
  static userIsAuthenticated(client: any, response: any): void {
    expect(client.isAuthenticated()).toBe(true);
    expect(response.AccessToken).toBeTruthy();
    expect(response.User).toBeDefined();
  }

  /**
   * Then: User is not authenticated
   */
  static userIsNotAuthenticated(client: any): void {
    expect(client.isAuthenticated()).toBe(false);
  }

  /**
   * Then: Authentication fails
   */
  static authenticationFails(error: any): void {
    expect(error).toBeDefined();
  }
}

/**
 * Then: Library expectations
 */
export class ThenLibrary {
  /**
   * Then: Search returns results
   */
  static searchReturnsResults(results: any, searchTerm: string): void {
    expect(
      results.albums.length > 0 || results.artists.length > 0 || results.tracks.length > 0
    ).toBe(true);
  }

  /**
   * Then: Search returns no results
   */
  static searchReturnsNoResults(results: any): void {
    expect(results.albums).toHaveLength(0);
    expect(results.artists).toHaveLength(0);
    expect(results.tracks).toHaveLength(0);
  }

  /**
   * Then: Albums are returned
   */
  static albumsAreReturned(result: any, expectedCount?: number): void {
    expect(result.Items).toBeDefined();
    expect(Array.isArray(result.Items)).toBe(true);
    if (expectedCount !== undefined) {
      expect(result.Items.length).toBe(expectedCount);
    }
  }

  /**
   * Then: Item has favorite status
   */
  static itemIsFavorited(item: any): void {
    expect(item.UserData?.IsFavorite).toBe(true);
  }

  /**
   * Then: Item is not favorited
   */
  static itemIsNotFavorited(item: any): void {
    expect(item.UserData?.IsFavorite).toBe(false);
  }
}

/**
 * Then: Queue expectations
 */
export class ThenQueue {
  /**
   * Then: Queue is empty
   */
  static queueIsEmpty(queue: any): void {
    expect(queue.isEmpty()).toBe(true);
    expect(queue.getCurrentItem()).toBeNull();
    expect(queue.getAllItems()).toHaveLength(0);
  }

  /**
   * Then: Queue has N tracks
   */
  static queueHasTracks(queue: any, count: number): void {
    expect(queue.getAllItems()).toHaveLength(count);
    expect(queue.isEmpty()).toBe(false);
  }

  /**
   * Then: Current track is
   */
  static currentTrackIs(queue: any, expectedTrack: AudioItem): void {
    expect(queue.getCurrentItem()).toEqual(expectedTrack);
  }

  /**
   * Then: Has next track
   */
  static hasNextTrack(queue: any): void {
    expect(queue.hasNext()).toBe(true);
  }

  /**
   * Then: Has no next track
   */
  static hasNoNextTrack(queue: any): void {
    expect(queue.hasNext()).toBe(false);
  }

  /**
   * Then: Has previous track
   */
  static hasPreviousTrack(queue: any): void {
    expect(queue.hasPrevious()).toBe(true);
  }

  /**
   * Then: Has no previous track
   */
  static hasNoPreviousTrack(queue: any): void {
    expect(queue.hasPrevious()).toBe(false);
  }

  /**
   * Then: Shuffle mode is enabled
   */
  static shuffleIsEnabled(queue: any): void {
    expect(queue.getShuffleMode()).toBe('on');
  }

  /**
   * Then: Shuffle mode is disabled
   */
  static shuffleIsDisabled(queue: any): void {
    expect(queue.getShuffleMode()).toBe('off');
  }

  /**
   * Then: Repeat mode is
   */
  static repeatModeIs(queue: any, mode: 'off' | 'all' | 'one'): void {
    expect(queue.getRepeatMode()).toBe(mode);
  }

  /**
   * Then: Current track continues playing (after shuffle/mode change)
   */
  static currentTrackContinuesPlaying(queue: any, trackBeforeChange: AudioItem): void {
    expect(queue.getCurrentItem()).toEqual(trackBeforeChange);
  }
}

/**
 * Then: Playlist expectations
 */
export class ThenPlaylist {
  /**
   * Then: Playlist is created
   */
  static playlistIsCreated(playlist: any, expectedName: string): void {
    expect(playlist).toBeDefined();
    expect(playlist.Id).toBeTruthy();
    expect(playlist.Name).toBe(expectedName);
  }

  /**
   * Then: Playlist has N tracks
   */
  static playlistHasTracks(items: any, count: number): void {
    expect(items.Items).toHaveLength(count);
  }

  /**
   * Then: Playlist is deleted
   */
  static async playlistIsDeleted(playlistsService: any, playlistId: string): Promise<void> {
    await expect(playlistsService.getPlaylist(playlistId)).rejects.toThrow();
  }
}

/**
 * Scenario context builder for fluent BDD syntax
 */
export class ScenarioContext {
  givenUser: GivenUser;
  givenLibrary: GivenLibrary;
  whenUser: WhenUser;

  constructor(private fixtures: TestFixtures) {
    this.givenUser = new GivenUser(fixtures);
    this.givenLibrary = new GivenLibrary(fixtures);
    this.whenUser = new WhenUser(fixtures);
  }

  get then() {
    return {
      auth: ThenAuth,
      library: ThenLibrary,
      queue: ThenQueue,
      playlist: ThenPlaylist,
    };
  }

  get when() {
    return {
      user: this.whenUser,
      queue: WhenQueue,
    };
  }

  get given() {
    return {
      user: this.givenUser,
      library: this.givenLibrary,
      queue: GivenQueue,
    };
  }
}
