import { useInfiniteQuery, keepPreviousData, type InfiniteData } from '@tanstack/react-query';
import { type ItemsResult, type MediaServerClient } from '@yay-tsa/core';
import { useAuthStore } from '@/features/auth/stores/auth.store';
import { usePrefetchArtwork, type ArtworkItem } from './usePrefetchArtwork';

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

export function useInfiniteLibraryQuery<TData extends ArtworkItem>({
  queryKey,
  fetcher,
  options = {},
  enabled = true,
  staleTime = 5 * 60 * 1000,
}: UseInfiniteLibraryQueryOptions<TData>) {
  const client = useAuthStore(state => state.client);
  const { limit = 50, ...otherOptions } = options;

  const query = useInfiniteQuery<
    ItemsResult<TData>,
    Error,
    InfiniteData<ItemsResult<TData>>,
    unknown[],
    number
  >({
    queryKey: [...queryKey, { limit, ...otherOptions }],
    queryFn: async ({ pageParam = 0 }) => {
      if (!client) throw new Error('Not authenticated');

      return fetcher(client, {
        ...otherOptions,
        startIndex: pageParam,
        limit,
      });
    },
    initialPageParam: 0,
    // Bound retained pages so a 50k-track library doesn't pin ~1000 pages in memory.
    // Offset is derived from the last page's own param (not the running sum), so it stays
    // correct after maxPages evicts the oldest pages.
    maxPages: MAX_RETAINED_PAGES,
    getNextPageParam: (lastPage: ItemsResult<TData>, _allPages, lastPageParam: number) => {
      const next = lastPageParam + (lastPage.Items?.length ?? 0);
      if (next >= lastPage.TotalRecordCount) return undefined;
      return next;
    },
    getPreviousPageParam: (_firstPage, _allPages, firstPageParam: number) => {
      if (firstPageParam <= 0) return undefined;
      return Math.max(0, firstPageParam - limit);
    },
    enabled: enabled && !!client,
    staleTime,
    placeholderData: keepPreviousData,
  });

  usePrefetchArtwork({ data: query.data });

  return query;
}

const MAX_RETAINED_PAGES = 50;
