import { AUDIOBOOK_GENRES, ItemsService, type MusicAlbum } from '@yay-tsa/core';
import { useAuthenticatedQuery } from '@/features/auth/hooks/useAuthenticatedQuery';
import { useInfiniteLibraryQuery } from './useInfiniteLibraryQuery';

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
