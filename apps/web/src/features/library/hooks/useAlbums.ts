import { useQuery } from '@tanstack/react-query';
import { ItemsService, type MusicAlbum } from '@yaytsa/core';
import { useAuthStore } from '@/features/auth/stores/auth.store';

interface UseAlbumsOptions {
  startIndex?: number;
  limit?: number;
  searchTerm?: string;
  sortBy?: string;
  artistId?: string;
  isFavorite?: boolean;
  enabled?: boolean;
}

export function useAlbums(options: UseAlbumsOptions = {}) {
  const client = useAuthStore(state => state.client);
  const { enabled = true, ...queryOptions } = options;

  return useQuery({
    queryKey: ['albums', queryOptions],
    queryFn: async () => {
      if (!client) throw new Error('Not authenticated');
      const itemsService = new ItemsService(client);
      return itemsService.getAlbums(queryOptions);
    },
    enabled: enabled && !!client,
    staleTime: 5 * 60 * 1000,
  });
}

export function useRecentAlbums(limit = 20) {
  const client = useAuthStore(state => state.client);

  return useQuery({
    queryKey: ['albums', 'recent', limit],
    queryFn: async () => {
      if (!client) throw new Error('Not authenticated');
      const itemsService = new ItemsService(client);
      return itemsService.getRecentAlbums(limit);
    },
    enabled: !!client,
    staleTime: 5 * 60 * 1000,
  });
}

export function useRecentlyPlayedAlbums(limit = 20) {
  const client = useAuthStore(state => state.client);

  return useQuery({
    queryKey: ['albums', 'recentlyPlayed', limit],
    queryFn: async () => {
      if (!client) throw new Error('Not authenticated');
      const itemsService = new ItemsService(client);
      return itemsService.getRecentlyPlayedAlbums(limit);
    },
    enabled: !!client,
    staleTime: 5 * 60 * 1000,
  });
}

export function useAlbumTracks(albumId: string | undefined) {
  const client = useAuthStore(state => state.client);

  return useQuery({
    queryKey: ['album', albumId, 'tracks'],
    queryFn: async () => {
      if (!client) throw new Error('Not authenticated');
      if (!albumId) throw new Error('Album ID required');
      const itemsService = new ItemsService(client);
      return itemsService.getAlbumTracks(albumId);
    },
    enabled: !!client && !!albumId,
    staleTime: 5 * 60 * 1000,
  });
}

export type { MusicAlbum };
