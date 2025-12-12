/**
 * Library store
 * Manages music library data (albums, artists, tracks)
 */

import { writable, derived, get } from 'svelte/store';
import { ItemsService, type MusicAlbum, type MusicArtist, type AudioItem } from '@yaytsa/core';
import { client, isAuthenticated } from './auth.js';
import { createAsyncStoreHandler, createServiceWaiter } from './utils.js';
import {
  getCachedAlbums,
  getCachedRecentAlbums,
  getCachedAlbumTracks,
  getCachedRecentlyPlayedAlbums,
  getCachedArtists,
  getCachedArtistAlbums,
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
 */
async function loadAlbums(options?: {
  limit?: number;
  startIndex?: number;
  sortBy?: string;
  append?: boolean;
}): Promise<void> {
  const handler = createAsyncStoreHandler(libraryStore);
  handler.start();

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

    const nextAlbums = startIndex === 0 ? result.Items : [...state.albums, ...result.Items];

    handler.success({
      albums: nextAlbums,
      albumsTotal: result.TotalRecordCount,
      albumsSort: sortBy,
    });
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
async function loadArtists(options?: {
  limit?: number;
  startIndex?: number;
  sortBy?: string;
  append?: boolean;
}): Promise<void> {
  const handler = createAsyncStoreHandler(libraryStore);
  handler.start();

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

    const nextArtists = startIndex === 0 ? result.Items : [...state.artists, ...result.Items];

    handler.success({
      artists: nextArtists,
      artistsTotal: result.TotalRecordCount,
      artistsSort: sortBy,
    });
  } catch (error) {
    handler.error(error as Error);
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
