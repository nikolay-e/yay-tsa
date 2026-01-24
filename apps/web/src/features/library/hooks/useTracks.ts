import { useInfiniteQuery } from '@tanstack/react-query';
import { ItemsService, type AudioItem } from '@yaytsa/core';
import { useAuthStore } from '@/features/auth/stores/auth.store';

interface UseInfiniteTracksOptions {
  limit?: number;
  searchTerm?: string;
  sortBy?: string;
  artistId?: string;
  albumId?: string;
  isFavorite?: boolean;
}

export function useInfiniteTracks(options: UseInfiniteTracksOptions = {}) {
  const client = useAuthStore(state => state.client);
  const { limit = 50, ...queryOptions } = options;

  return useInfiniteQuery({
    queryKey: ['tracks', 'infinite', queryOptions],
    queryFn: async ({ pageParam = 0 }) => {
      if (!client) throw new Error('Not authenticated');
      const itemsService = new ItemsService(client);
      return itemsService.getTracks({
        ...queryOptions,
        startIndex: pageParam,
        limit,
      });
    },
    initialPageParam: 0,
    getNextPageParam: (lastPage, allPages) => {
      const loadedCount = allPages.reduce((sum, page) => sum + page.Items.length, 0);
      if (loadedCount >= lastPage.TotalRecordCount) return undefined;
      return loadedCount;
    },
    enabled: !!client,
    staleTime: 5 * 60 * 1000,
  });
}

export type { AudioItem };
