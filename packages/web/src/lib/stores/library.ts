/**
 * Library store
 * Manages music library data (albums, artists, tracks)
 */

import { writable, derived, get } from 'svelte/store';
import { ItemsService, type MusicAlbum, type MusicArtist, type AudioItem } from '@yaytsa/core';
import { client } from './auth.js';
import { createAsyncStoreHandler } from './utils.js';
import {
  getCachedAlbums,
  getCachedRecentAlbums,
  getCachedAlbumTracks,
} from '../services/cached-items-service.js';
import { preloadAlbumArts } from '../utils/image.js';

interface LibraryState {
  itemsService: ItemsService | null;
  albums: MusicAlbum[];
  artists: MusicArtist[];
  tracks: AudioItem[];
  currentAlbum: MusicAlbum | null;
  currentArtist: MusicArtist | null;
  isLoading: boolean;
  error: string | null;
}

const initialState: LibraryState = {
  itemsService: null,
  albums: [],
  artists: [],
  tracks: [],
  currentAlbum: null,
  currentArtist: null,
  isLoading: false,
  error: null,
};

const libraryStore = writable<LibraryState>(initialState);

// Race condition prevention for search
let currentSearchQuery: string | null = null;

// Initialize items service when client is available
client.subscribe($client => {
  if ($client) {
    const itemsService = new ItemsService($client);
    libraryStore.update(state => ({ ...state, itemsService }));
  } else {
    libraryStore.update(state => ({ ...state, itemsService: null }));
  }
});

/**
 * Wait for items service to be initialized
 * Uses reactive subscription instead of polling for better performance
 */
async function waitForService(): Promise<ItemsService> {
  const state = get(libraryStore);

  // If service is already available, return it immediately
  if (state.itemsService) {
    return state.itemsService;
  }

  // Otherwise, wait for the service to be initialized via subscription
  return new Promise((resolve, reject) => {
    let resolved = false;

    const timeout = setTimeout(() => {
      if (!resolved) {
        resolved = true;
        unsubscribe();
        reject(new Error('Items service not initialized after 5 seconds'));
      }
    }, 5000);

    const unsubscribe = libraryStore.subscribe($state => {
      if ($state.itemsService && !resolved) {
        resolved = true;
        clearTimeout(timeout);
        unsubscribe();
        resolve($state.itemsService);
      }
    });
  });
}

/**
 * Load albums from server (with caching)
 */
async function loadAlbums(options?: { limit?: number; startIndex?: number }): Promise<void> {
  const handler = createAsyncStoreHandler(libraryStore);
  handler.start();

  try {
    const itemsService = await waitForService();

    // Use cached version
    const result = await getCachedAlbums(itemsService, {
      limit: options?.limit,
      startIndex: options?.startIndex,
      sortBy: 'SortName',
    });

    handler.success({ albums: result.Items });

    // Preload album art in background (non-blocking)
    if (result.Items.length > 0) {
      const albumIds = result.Items.map(album => album.Id);
      void preloadAlbumArts(albumIds, 'medium');
    }
  } catch (error) {
    handler.error(error as Error);
    throw error;
  }
}

/**
 * Load recent albums (with caching)
 */
async function loadRecentAlbums(limit: number = 20): Promise<void> {
  const handler = createAsyncStoreHandler(libraryStore);
  handler.start();

  try {
    const itemsService = await waitForService();

    // Use cached version (with shorter TTL for recent albums)
    const result = await getCachedRecentAlbums(itemsService, limit);

    handler.success({ albums: result.Items });

    // Preload album art in background (non-blocking)
    if (result.Items.length > 0) {
      const albumIds = result.Items.map(album => album.Id);
      void preloadAlbumArts(albumIds, 'medium');
    }
  } catch (error) {
    handler.error(error as Error);
    throw error;
  }
}

/**
 * Load artists from server
 */
async function loadArtists(options?: { limit?: number; startIndex?: number }): Promise<void> {
  const handler = createAsyncStoreHandler(libraryStore);
  handler.start();

  try {
    const itemsService = await waitForService();

    const result = await itemsService.getArtists({
      limit: options?.limit,
      startIndex: options?.startIndex,
    });

    handler.success({ artists: result.Items });
  } catch (error) {
    handler.error(error as Error);
    throw error;
  }
}

/**
 * Load tracks for a specific album (with caching)
 */
async function loadAlbumTracks(albumId: string): Promise<AudioItem[]> {
  const handler = createAsyncStoreHandler(libraryStore);
  handler.start();

  try {
    const itemsService = await waitForService();

    // Use cached version (album tracks rarely change - long TTL)
    const items = await getCachedAlbumTracks(itemsService, albumId);

    handler.success({ tracks: items });
    return items;
  } catch (error) {
    handler.error(error as Error);
    throw error;
  }
}

/**
 * Load albums for a specific artist
 * Returns albums directly without overwriting the global albums store
 */
async function loadArtistAlbums(artistId: string): Promise<MusicAlbum[]> {
  const handler = createAsyncStoreHandler(libraryStore);
  handler.start();

  try {
    const itemsService = await waitForService();
    const result = await itemsService.getArtistAlbums(artistId);

    handler.success({});
    return result;
  } catch (error) {
    handler.error(error as Error);
    throw error;
  }
}

/**
 * Search for albums, artists, and tracks
 */
async function search(query: string): Promise<void> {
  const searchQuery = query.trim();

  if (!searchQuery) {
    libraryStore.update(s => ({ ...s, albums: [], artists: [], tracks: [] }));
    currentSearchQuery = null;
    return;
  }

  // Set current search query for race condition detection
  currentSearchQuery = searchQuery;

  const handler = createAsyncStoreHandler(libraryStore);
  handler.start();

  try {
    const itemsService = await waitForService();
    const { albums, artists, tracks } = await itemsService.search(searchQuery, { limit: 50 });

    // Only update if this is still the current search query
    if (currentSearchQuery === searchQuery) {
      handler.success({ albums, artists, tracks });
    } else {
      // Search was superseded, discard results silently
      return;
    }
  } catch (error) {
    // Only report error if this is still the current search query
    if (currentSearchQuery === searchQuery) {
      handler.error(error as Error);
    }
    throw error;
  }
}

/**
 * Set current album (for detail view)
 */
function setCurrentAlbum(album: MusicAlbum | null): void {
  libraryStore.update(s => ({ ...s, currentAlbum: album }));
}

/**
 * Set current artist (for detail view)
 */
function setCurrentArtist(artist: MusicArtist | null): void {
  libraryStore.update(s => ({ ...s, currentArtist: artist }));
}

/**
 * Clear library data
 */
function clear(): void {
  libraryStore.set(initialState);
}

// Derived stores
export const albums = derived(libraryStore, $library => $library.albums);
export const artists = derived(libraryStore, $library => $library.artists);
export const tracks = derived(libraryStore, $library => $library.tracks);
export const currentAlbum = derived(libraryStore, $library => $library.currentAlbum);
export const currentArtist = derived(libraryStore, $library => $library.currentArtist);
export const isLoading = derived(libraryStore, $library => $library.isLoading);
export const error = derived(libraryStore, $library => $library.error);

export const library = {
  subscribe: libraryStore.subscribe,
  loadAlbums,
  loadRecentAlbums,
  loadArtists,
  loadAlbumTracks,
  loadArtistAlbums,
  search,
  setCurrentAlbum,
  setCurrentArtist,
  clear,
  getService: () => get(libraryStore).itemsService,
};
