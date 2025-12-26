/**
 * Library store
 * Manages music library data (albums, artists, tracks)
 */

import { writable, derived, get } from 'svelte/store';
import { browser } from '$app/environment';
import { ItemsService, type MusicAlbum, type MusicArtist, type AudioItem } from '@yaytsa/core';
import { client, isAuthenticated } from '../auth/auth.store.js';
import { createAsyncStoreHandler, createServiceWaiter } from '../../shared/utils/store-utils.js';
import {
  getCachedAlbums,
  getCachedRecentAlbums,
  getCachedAlbumTracks,
  getCachedRecentlyPlayedAlbums,
  getCachedArtists,
  getCachedArtistAlbums,
} from './cached-items.service.js';

interface LibraryState {
  itemsService: ItemsService | null;
  albums: MusicAlbum[];
  artists: MusicArtist[];
  tracks: AudioItem[];
  currentAlbum: MusicAlbum | null;
  currentArtist: MusicArtist | null;
  isLoading: boolean;
  error: string | null;
  albumsTotal: number;
  artistsTotal: number;
  albumsSort: string;
  artistsSort: string;
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
  albumsTotal: 0,
  artistsTotal: 0,
  albumsSort: 'SortName',
  artistsSort: 'SortName',
};

const libraryStore = writable<LibraryState>(initialState);

const SERVICE_INIT_TIMEOUT_MS = 5000;
const PAGE_SIZE = 60;

// Track current pagination operations to prevent race conditions
let albumsLoadOperationId = 0;
let artistsLoadOperationId = 0;
let clientUnsubscribe: (() => void) | null = null;

// Initialize items service when client is available
// HMR disposal is handled via import.meta.hot.dispose() below
if (browser) {
  clientUnsubscribe = client.subscribe($client => {
    if ($client) {
      const itemsService = new ItemsService($client);
      libraryStore.update(state => ({ ...state, itemsService }));
    } else {
      // Clear all library data when client is null (logout)
      libraryStore.set(initialState);
    }
  });

  // HMR cleanup - dispose subscription on module reload
  if (import.meta.hot) {
    import.meta.hot.dispose(() => {
      if (clientUnsubscribe) {
        clientUnsubscribe();
        clientUnsubscribe = null;
      }
    });
  }
}

// Create service waiter using shared utility
const serviceStore = derived(libraryStore, $s => ({ service: $s.itemsService }));
const waitForService = createServiceWaiter(
  serviceStore,
  isAuthenticated,
  SERVICE_INIT_TIMEOUT_MS,
  'Items service'
);

/**
 * Load albums from server (with caching)
 * Uses operation ID to prevent race conditions with concurrent pagination calls
 */
async function loadAlbums(options?: {
  limit?: number;
  startIndex?: number;
  sortBy?: string;
  append?: boolean;
}): Promise<void> {
  const handler = createAsyncStoreHandler(libraryStore);
  handler.start();

  // Increment operation ID and capture it for this operation
  const currentOpId = ++albumsLoadOperationId;

  try {
    const itemsService = await waitForService();
    const state = get(libraryStore);

    const sortBy = options?.sortBy || state.albumsSort || 'SortName';
    const limit = options?.limit ?? PAGE_SIZE;
    const startIndex =
      options?.append === true
        ? state.albums.length
        : options?.startIndex !== undefined
          ? options.startIndex
          : 0;

    // Use cached version
    const result = await getCachedAlbums(itemsService, {
      limit,
      startIndex,
      sortBy,
    });

    // Check if this operation is still current (prevents race condition with concurrent calls)
    if (currentOpId !== albumsLoadOperationId) {
      return;
    }

    // Re-read state to get current albums for append (may have changed during await)
    const currentState = get(libraryStore);
    const nextAlbums = startIndex === 0 ? result.Items : [...currentState.albums, ...result.Items];

    handler.success({
      albums: nextAlbums,
      albumsTotal: result.TotalRecordCount,
      albumsSort: sortBy,
    });
  } catch (error) {
    // Only handle error if this operation is still current
    if (currentOpId === albumsLoadOperationId) {
      handler.error(error as Error);
    }
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
 * Uses operation ID to prevent race conditions with concurrent pagination calls
 */
async function loadArtists(options?: {
  limit?: number;
  startIndex?: number;
  sortBy?: string;
  append?: boolean;
}): Promise<void> {
  const handler = createAsyncStoreHandler(libraryStore);
  handler.start();

  // Increment operation ID and capture it for this operation
  const currentOpId = ++artistsLoadOperationId;

  try {
    const itemsService = await waitForService();
    const state = get(libraryStore);

    const sortBy = options?.sortBy || state.artistsSort || 'SortName';
    const limit = options?.limit ?? PAGE_SIZE;
    const startIndex =
      options?.append === true
        ? state.artists.length
        : options?.startIndex !== undefined
          ? options.startIndex
          : 0;

    const result = await getCachedArtists(itemsService, {
      limit,
      startIndex,
      sortBy,
    });

    // Check if this operation is still current (prevents race condition with concurrent calls)
    if (currentOpId !== artistsLoadOperationId) {
      return;
    }

    // Re-read state to get current artists for append (may have changed during await)
    const currentState = get(libraryStore);
    const nextArtists =
      startIndex === 0 ? result.Items : [...currentState.artists, ...result.Items];

    handler.success({
      artists: nextArtists,
      artistsTotal: result.TotalRecordCount,
      artistsSort: sortBy,
    });
  } catch (error) {
    // Only handle error if this operation is still current
    if (currentOpId === artistsLoadOperationId) {
      handler.error(error as Error);
    }
    throw error;
  }
}

async function loadArtistAlbums(artistId: string): Promise<MusicAlbum[]> {
  const itemsService = await waitForService();
  return getCachedArtistAlbums(itemsService, artistId);
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
export const albumsTotal = derived(libraryStore, $library => $library.albumsTotal);
export const hasMoreAlbums = derived(
  libraryStore,
  $library => $library.albums.length < $library.albumsTotal
);
export const artists = derived(libraryStore, $library => $library.artists);
export const artistsTotal = derived(libraryStore, $library => $library.artistsTotal);
export const hasMoreArtists = derived(
  libraryStore,
  $library => $library.artists.length < $library.artistsTotal
);
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
