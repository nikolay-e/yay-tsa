/**
 * Library store
 * Manages music library data (albums, artists, tracks)
 */

import { writable, derived, get } from 'svelte/store';
import { ItemsService, type MusicAlbum, type MusicArtist, type AudioItem } from '@yaytsa/core';
import { client, isAuthenticated } from './auth.js';
import { createAsyncStoreHandler } from './utils.js';
import {
  getCachedAlbums,
  getCachedRecentAlbums,
  getCachedAlbumTracks,
  getCachedRecentlyPlayedAlbums,
} from '../services/cached-items-service.js';

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

const SERVICE_INIT_TIMEOUT_MS = 5000;

// Initialize items service when client is available
client.subscribe($client => {
  if ($client) {
    const itemsService = new ItemsService($client);
    libraryStore.update(state => ({ ...state, itemsService }));
  } else {
    // Clear all library data when client is null (logout)
    libraryStore.set(initialState);
  }
});

/**
 * Wait for items service to be initialized
 * Uses reactive subscription instead of polling for better performance
 */
async function waitForService(): Promise<ItemsService> {
  // Check if user is authenticated first
  if (!get(isAuthenticated)) {
    throw new Error('Not authenticated');
  }

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
        reject(new Error(`Items service not initialized after ${SERVICE_INIT_TIMEOUT_MS}ms`));
      }
    }, SERVICE_INIT_TIMEOUT_MS);

    const unsubscribe = libraryStore.subscribe($state => {
      if ($state.itemsService && !resolved) {
        resolved = true;
        clearTimeout(timeout);
        // Defer unsubscribe to next microtask for safety
        queueMicrotask(() => unsubscribe());
        resolve($state.itemsService);
      }
    });
  });
}

/**
 * Load albums from server (with caching)
 */
async function loadAlbums(options?: {
  limit?: number;
  startIndex?: number;
  sortBy?: string;
}): Promise<void> {
  const handler = createAsyncStoreHandler(libraryStore);
  handler.start();

  try {
    const itemsService = await waitForService();

    // Use cached version
    const result = await getCachedAlbums(itemsService, {
      limit: options?.limit,
      startIndex: options?.startIndex,
      sortBy: options?.sortBy || 'SortName',
    });

    handler.success({ albums: result.Items });
  } catch (error) {
    handler.error(error as Error);
    throw error;
  }
}

/**
 * Load recent albums (with caching)
 */
async function loadRecentAlbums(limit?: number): Promise<void> {
  const handler = createAsyncStoreHandler(libraryStore);
  handler.start();

  try {
    const itemsService = await waitForService();

    // Use cached version (with shorter TTL for recent albums)
    const result = await getCachedRecentAlbums(itemsService, limit);

    handler.success({ albums: result.Items });
  } catch (error) {
    handler.error(error as Error);
    throw error;
  }
}

/**
 * Load recently played albums (with fallback to random)
 * Returns info about whether random albums were used
 */
async function loadRecentlyPlayedAlbums(limit: number = 24): Promise<{ isRandom: boolean }> {
  const handler = createAsyncStoreHandler(libraryStore);
  handler.start();

  try {
    const itemsService = await waitForService();

    const result = await getCachedRecentlyPlayedAlbums(itemsService, limit);

    handler.success({ albums: result.items });
    return { isRandom: result.isRandom };
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
 * Get album tracks without updating global store
 * Use this for playback - doesn't pollute search results
 */
async function getAlbumTracks(albumId: string): Promise<AudioItem[]> {
  const itemsService = await waitForService();
  // Use cached version (album tracks rarely change - long TTL)
  return getCachedAlbumTracks(itemsService, albumId);
}

/**
 * Load tracks for a specific album (with caching)
 * Updates global store - use only when displaying tracks in UI
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
  loadRecentlyPlayedAlbums,
  loadArtists,
  loadAlbumTracks,
  getAlbumTracks,
  loadArtistAlbums,
  setCurrentAlbum,
  setCurrentArtist,
  clear,
  getService: () => get(libraryStore).itemsService,
};
