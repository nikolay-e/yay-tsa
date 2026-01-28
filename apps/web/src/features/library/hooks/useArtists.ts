import { useQuery } from '@tanstack/react-query';
import { ItemsService, type MusicArtist, type MusicAlbum, type AudioItem } from '@yaytsa/core';
import { useAuthStore } from '@/features/auth/stores/auth.store';
import { useInfiniteLibraryQuery } from './useInfiniteLibraryQuery';

interface UseArtistsOptions {
  startIndex?: number;
  limit?: number;
  searchTerm?: string;
  sortBy?: string;
  isFavorite?: boolean;
  enabled?: boolean;
}

export function useArtists(options: UseArtistsOptions = {}) {
  const client = useAuthStore(state => state.client);
  const { enabled = true, ...queryOptions } = options;

  return useQuery({
    queryKey: ['artists', queryOptions],
    queryFn: async () => {
      if (!client) throw new Error('Not authenticated');
      const itemsService = new ItemsService(client);
      return itemsService.getArtists(queryOptions);
    },
    enabled: enabled && !!client,
    staleTime: 5 * 60 * 1000,
  });
}

interface UseInfiniteArtistsOptions {
  limit?: number;
  searchTerm?: string;
  sortBy?: string;
  isFavorite?: boolean;
}

export function useInfiniteArtists(options: UseInfiniteArtistsOptions = {}) {
  const { limit = 50, ...queryOptions } = options;

  return useInfiniteLibraryQuery<MusicArtist>({
    queryKey: ['artists', 'infinite'],
    options: { limit, ...queryOptions },
    fetcher: async (client, params) => {
      const itemsService = new ItemsService(client);
      return itemsService.getArtists(params);
    },
  });
}

export function useArtist(artistId: string | undefined) {
  const client = useAuthStore(state => state.client);

  return useQuery({
    queryKey: ['artist', artistId],
    queryFn: async () => {
      if (!client) throw new Error('Not authenticated');
      if (!artistId) throw new Error('Artist ID required');
      const itemsService = new ItemsService(client);
      return itemsService.getItem(artistId) as Promise<MusicArtist>;
    },
    enabled: !!client && !!artistId,
    staleTime: 5 * 60 * 1000,
  });
}

export function useArtistAlbums(artistId: string | undefined) {
  const client = useAuthStore(state => state.client);

  return useQuery({
    queryKey: ['artist', artistId, 'albums'],
    queryFn: async () => {
      if (!client) throw new Error('Not authenticated');
      if (!artistId) throw new Error('Artist ID required');
      const itemsService = new ItemsService(client);
      return itemsService.getArtistAlbums(artistId);
    },
    enabled: !!client && !!artistId,
    staleTime: 5 * 60 * 1000,
  });
}

export function useArtistTracks(artistId: string | undefined) {
  const client = useAuthStore(state => state.client);

  return useQuery({
    queryKey: ['artist', artistId, 'tracks'],
    queryFn: async () => {
      if (!client) throw new Error('Not authenticated');
      if (!artistId) throw new Error('Artist ID required');
      const itemsService = new ItemsService(client);
      return itemsService.getArtistTracks(artistId);
    },
    enabled: !!client && !!artistId,
    staleTime: 5 * 60 * 1000,
  });
}

export type { MusicArtist, MusicAlbum, AudioItem };
