import { ItemsService, type MusicAlbum } from '@yay-tsa/core';
import { useAuthenticatedQuery } from '@/features/auth/hooks/useAuthenticatedQuery';
import { useInfiniteLibraryQuery } from './useInfiniteLibraryQuery';

interface UseAlbumsOptions {
  startIndex?: number;
  limit?: number;
  searchTerm?: string;
  sortBy?: string;
  sortOrder?: 'Ascending' | 'Descending';
  artistId?: string;
  isFavorite?: boolean;
  enabled?: boolean;
}

export function useAlbums(options: UseAlbumsOptions = {}) {
  const { enabled = true, ...queryOptions } = options;

  return useAuthenticatedQuery(
    ['albums', queryOptions],
    async client => new ItemsService(client).getAlbums(queryOptions),
    { enabled }
  );
}

interface UseInfiniteAlbumsOptions {
  limit?: number;
  searchTerm?: string;
  sortBy?: string;
  sortOrder?: 'Ascending' | 'Descending';
  artistId?: string;
  isFavorite?: boolean;
}

export function useInfiniteAlbums(options: UseInfiniteAlbumsOptions = {}) {
  const { limit = 50, ...queryOptions } = options;

  return useInfiniteLibraryQuery<MusicAlbum>({
    queryKey: ['albums', 'infinite'],
    options: { limit, ...queryOptions },
    fetcher: async (client, params) => {
      const itemsService = new ItemsService(client);
      return itemsService.getAlbums(params);
    },
  });
}

export function useRecentAlbums(limit = 20) {
  return useAuthenticatedQuery(['albums', 'recent', limit], async client =>
    new ItemsService(client).getRecentAlbums(limit)
  );
}

export function useAlbumTracks(albumId: string | undefined) {
  return useAuthenticatedQuery(
    ['album', albumId, 'tracks'],
    async client => {
      if (!albumId) throw new Error('Album ID required');
      return new ItemsService(client).getAlbumTracks(albumId);
    },
    { enabled: !!albumId }
  );
}

export type { MusicAlbum } from '@yay-tsa/core';
