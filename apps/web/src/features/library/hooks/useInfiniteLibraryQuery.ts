import { useInfiniteQuery, type InfiniteData } from '@tanstack/react-query';
import { type ItemsResult, type MediaServerClient } from '@yay-tsa/core';
import { useAuthStore } from '@/features/auth/stores/auth.store';

interface LibraryQueryOptions {
  limit?: number;
  [key: string]: unknown;
}

interface LibraryQueryParams extends LibraryQueryOptions {
  startIndex: number;
}

interface UseInfiniteLibraryQueryOptions<TData> {
  queryKey: unknown[];
  fetcher: (client: MediaServerClient, params: LibraryQueryParams) => Promise<ItemsResult<TData>>;
  options?: LibraryQueryOptions;
  enabled?: boolean;
  staleTime?: number;
}

export function useInfiniteLibraryQuery<TData>({
  queryKey,
  fetcher,
  options = {},
  enabled = true,
  staleTime = 5 * 60 * 1000,
}: UseInfiniteLibraryQueryOptions<TData>) {
  const client = useAuthStore(state => state.client);
  const { limit = 50, ...otherOptions } = options;

  return useInfiniteQuery<ItemsResult<TData>, Error, InfiniteData<ItemsResult<TData>>>({
    queryKey: [...queryKey, { limit, ...otherOptions }],
    queryFn: async ({ pageParam = 0 }) => {
      if (!client) throw new Error('Not authenticated');

      return fetcher(client, {
        ...otherOptions,
        startIndex: pageParam as number,
        limit,
      });
    },
    initialPageParam: 0,
    getNextPageParam: (lastPage: ItemsResult<TData>, allPages: ItemsResult<TData>[]) => {
      const loadedCount = allPages.reduce((sum, page) => sum + (page.Items?.length ?? 0), 0);
      if (loadedCount >= lastPage.TotalRecordCount) return undefined;
      return loadedCount;
    },
    enabled: enabled && !!client,
    staleTime,
  });
}
