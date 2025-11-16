import { writable, derived, get } from 'svelte/store';
import { ItemsService, type MusicAlbum, type MusicArtist, type AudioItem } from '@yaytsa/core';
import { client } from './auth.js';
import { logger } from '../utils/logger.js';

interface SearchState {
  itemsService: ItemsService | null;
  query: string;
  albums: MusicAlbum[];
  artists: MusicArtist[];
  tracks: AudioItem[];
  isSearching: boolean;
  error: string | null;
}

const initialState: SearchState = {
  itemsService: null,
  query: '',
  albums: [],
  artists: [],
  tracks: [],
  isSearching: false,
  error: null,
};

const searchStore = writable<SearchState>(initialState);

let currentSearchQuery: string | null = null;
const SERVICE_INIT_TIMEOUT_MS = 5000;

client.subscribe($client => {
  if ($client) {
    const itemsService = new ItemsService($client);
    searchStore.update(state => ({ ...state, itemsService }));
  } else {
    searchStore.set(initialState);
  }
});

async function waitForService(): Promise<ItemsService> {
  const state = get(searchStore);

  if (state.itemsService) {
    return state.itemsService;
  }

  return new Promise((resolve, reject) => {
    let resolved = false;

    const timeout = setTimeout(() => {
      if (!resolved) {
        resolved = true;
        unsubscribe();
        reject(new Error(`Items service not initialized after ${SERVICE_INIT_TIMEOUT_MS}ms`));
      }
    }, SERVICE_INIT_TIMEOUT_MS);

    const unsubscribe = searchStore.subscribe($state => {
      if ($state.itemsService && !resolved) {
        resolved = true;
        clearTimeout(timeout);
        queueMicrotask(() => unsubscribe());
        resolve($state.itemsService);
      }
    });
  });
}

async function search(query: string): Promise<void> {
  const searchQuery = query.trim();

  if (!searchQuery) {
    searchStore.update(s => ({
      ...s,
      query: '',
      albums: [],
      artists: [],
      tracks: [],
      isSearching: false,
      error: null,
    }));
    currentSearchQuery = null;
    return;
  }

  currentSearchQuery = searchQuery;

  searchStore.update(s => ({
    ...s,
    query: searchQuery,
    isSearching: true,
    error: null,
  }));

  try {
    const itemsService = await waitForService();
    const results = await itemsService.search(searchQuery, { limit: 50 });

    if (currentSearchQuery === searchQuery) {
      searchStore.update(s => ({
        ...s,
        albums: results.albums,
        artists: results.artists,
        tracks: results.tracks,
        isSearching: false,
        error: null,
      }));
    }
  } catch (error) {
    if (currentSearchQuery === searchQuery) {
      logger.error('Search error:', error);
      searchStore.update(s => ({
        ...s,
        isSearching: false,
        error: (error as Error).message,
      }));
    }
    throw error;
  }
}

function clear(): void {
  currentSearchQuery = null;
  searchStore.update(s => ({
    ...s,
    query: '',
    albums: [],
    artists: [],
    tracks: [],
    isSearching: false,
    error: null,
  }));
}

export const searchQuery = derived(searchStore, $s => $s.query);
export const searchAlbums = derived(searchStore, $s => $s.albums);
export const searchArtists = derived(searchStore, $s => $s.artists);
export const searchTracks = derived(searchStore, $s => $s.tracks);
export const isSearching = derived(searchStore, $s => $s.isSearching);
export const searchError = derived(searchStore, $s => $s.error);

export const searchService = {
  subscribe: searchStore.subscribe,
  search,
  clear,
};
