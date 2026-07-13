import { AUDIOBOOK_GENRES, ItemsService, type MusicArtist } from '@yay-tsa/core';
import { useAuthenticatedQuery } from '@/features/auth/hooks/useAuthenticatedQuery';
import { useInfiniteLibraryQuery } from './useInfiniteLibraryQuery';

interface UseInfiniteArtistsOptions {
  limit?: number;
  searchTerm?: string;
  sortBy?: string;
  sortOrder?: 'Ascending' | 'Descending';
  isFavorite?: boolean;
  excludeGenres?: string[];
}

export function useInfiniteArtists(options: UseInfiniteArtistsOptions = {}) {
  const { limit = 50, excludeGenres = AUDIOBOOK_GENRES, ...queryOptions } = options;

  return useInfiniteLibraryQuery<MusicArtist>({
    queryKey: ['artists', 'infinite'],
    options: { limit, excludeGenres, ...queryOptions },
    fetcher: async (client, params) => {
      const itemsService = new ItemsService(client);
      return itemsService.getArtists(params);
    },
  });
}

export function useArtist(artistId: string | undefined) {
  return useAuthenticatedQuery<MusicArtist>(
    ['artist', artistId],
    async client => {
      if (!artistId) throw new Error('Artist ID required');
      return new ItemsService(client).getItem(artistId) as Promise<MusicArtist>;
    },
    { enabled: !!artistId }
  );
}

export function useArtistAlbums(artistId: string | undefined) {
  return useAuthenticatedQuery(
    ['artist', artistId, 'albums'],
    async client => {
      if (!artistId) throw new Error('Artist ID required');
      return new ItemsService(client).getArtistAlbums(artistId);
    },
    { enabled: !!artistId }
  );
}

export type { MusicArtist, MusicAlbum, AudioItem } from '@yay-tsa/core';
