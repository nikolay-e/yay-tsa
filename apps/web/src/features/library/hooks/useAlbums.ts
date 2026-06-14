import { AUDIOBOOK_GENRES, ItemsService, type MusicAlbum } from '@yay-tsa/core';
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
  excludeGenres?: string[];
  enabled?: boolean;
}

export function useAlbums(options: UseAlbumsOptions = {}) {
  const { enabled = true, excludeGenres = AUDIOBOOK_GENRES, ...queryOptions } = options;

  return useAuthenticatedQuery(
    ['albums', { ...queryOptions, excludeGenres }],
    async client => new ItemsService(client).getAlbums({ ...queryOptions, excludeGenres }),
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
  excludeGenres?: string[];
}

export function useInfiniteAlbums(options: UseInfiniteAlbumsOptions = {}) {
  const { limit = 50, excludeGenres = AUDIOBOOK_GENRES, ...queryOptions } = options;

  return useInfiniteLibraryQuery<MusicAlbum>({
    queryKey: ['albums', 'infinite'],
    options: { limit, excludeGenres, ...queryOptions },
    fetcher: async (client, params) => {
      const itemsService = new ItemsService(client);
      return itemsService.getAlbums(params);
    },
  });
}

export function useRecentAlbums(limit = 20) {
  return useAuthenticatedQuery(['albums', 'recent', limit], async client =>
    new ItemsService(client).getAlbums({
      sortBy: 'DateCreated',
      sortOrder: 'Descending',
      limit,
      excludeGenres: AUDIOBOOK_GENRES,
    })
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
