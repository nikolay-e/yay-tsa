import { ItemsService, type AudioItem } from '@yaytsa/core';
import { useInfiniteLibraryQuery } from './useInfiniteLibraryQuery';

interface UseInfiniteTracksOptions {
  limit?: number;
  searchTerm?: string;
  sortBy?: string;
  artistId?: string;
  albumId?: string;
  isFavorite?: boolean;
}

export function useInfiniteTracks(options: UseInfiniteTracksOptions = {}) {
  const { limit = 50, ...queryOptions } = options;

  return useInfiniteLibraryQuery<AudioItem>({
    queryKey: ['tracks', 'infinite'],
    options: { limit, ...queryOptions },
    fetcher: async (client, params) => {
      const itemsService = new ItemsService(client);
      return itemsService.getTracks(params);
    },
  });
}

export type { AudioItem };
