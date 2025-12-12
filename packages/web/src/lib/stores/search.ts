import { writable, derived } from 'svelte/store';
import { ItemsService, type MusicAlbum, type MusicArtist, type AudioItem } from '@yaytsa/core';
import { client, isAuthenticated } from './auth.js';
import { logger } from '../utils/logger.js';
import { createAsyncStoreHandler, createServiceWaiter } from './utils.js';

interface SearchState {
  itemsService: ItemsService | null;
  query: string;
  albums: MusicAlbum[];
  artists: MusicArtist[];
  tracks: AudioItem[];
  isLoading: boolean;
  error: string | null;
}

const initialState: SearchState = {
  itemsService: null,
  query: '',
  albums: [],
  artists: [],
  tracks: [],
  isLoading: false,
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

const serviceStore = derived(searchStore, $s => ({ service: $s.itemsService }));
const waitForService = createServiceWaiter(
  serviceStore,
  isAuthenticated,
  SERVICE_INIT_TIMEOUT_MS,
  'Items service'
);

async function search(query: string): Promise<void> {
  const searchQuery = query.trim();

  if (!searchQuery) {
    searchStore.update(s => ({
      ...s,
      query: '',
      albums: [],
      artists: [],
      tracks: [],
      isLoading: false,
      error: null,
    }));
    currentSearchQuery = null;
    return;
  }

  currentSearchQuery = searchQuery;
  const handler = createAsyncStoreHandler(searchStore);

  searchStore.update(s => ({ ...s, query: searchQuery }));
  handler.start();

  try {
    const itemsService = await waitForService();
    const results = await itemsService.search(searchQuery, { limit: 50 });

    if (currentSearchQuery === searchQuery) {
      handler.success({
        albums: results.albums,
        artists: results.artists,
        tracks: results.tracks,
      });
    }
  } catch (error) {
    if (currentSearchQuery === searchQuery) {
      logger.error('Search error:', error);
      handler.error(error as Error);
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
    isLoading: false,
    error: null,
  }));
}

export const searchAlbums = derived(searchStore, $s => $s.albums);
export const searchTracks = derived(searchStore, $s => $s.tracks);
export const isSearching = derived(searchStore, $s => $s.isLoading);

export const searchService = {
  subscribe: searchStore.subscribe,
  search,
  clear,
};
