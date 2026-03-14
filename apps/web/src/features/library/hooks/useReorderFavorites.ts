import { useMutation, useQueryClient, type InfiniteData } from '@tanstack/react-query';
import { FavoritesService } from '@yay-tsa/core';
import { useAuthStore } from '@/features/auth/stores/auth.store';

interface PagedResponse {
  Items: { Id: string; [key: string]: unknown }[];
  TotalRecordCount: number;
}

type Snapshot = { queryKey: readonly unknown[]; data: unknown };

export function useReorderFavorites() {
  const client = useAuthStore(state => state.client);
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: async (itemIds: string[]) => {
      if (!client) throw new Error('Not authenticated');
      const service = new FavoritesService(client);
      await service.reorderFavorites(itemIds);
    },
    onMutate: async (itemIds: string[]) => {
      await queryClient.cancelQueries({ queryKey: ['albums'] });
      await queryClient.cancelQueries({ queryKey: ['artists'] });
      await queryClient.cancelQueries({ queryKey: ['tracks'] });

      const idSet = new Set(itemIds);
      const snapshots: Snapshot[] = [];

      for (const queryKey of [['albums'], ['artists'], ['tracks']]) {
        const queries = queryClient.getQueriesData<InfiniteData<PagedResponse>>({ queryKey });

        for (const [key, data] of queries) {
          if (!data?.pages) continue;

          const allItems = data.pages.flatMap(p => p.Items);
          const matchCount = allItems.filter(item => idSet.has(item.Id)).length;
          if (matchCount / allItems.length < 0.5) continue;

          snapshots.push({ queryKey: key, data });

          const itemMap = new Map(allItems.map(item => [item.Id, item]));
          const reordered = itemIds
            .map(id => itemMap.get(id))
            .filter((item): item is (typeof allItems)[number] => item != null);

          const newPages = data.pages.map((page, pageIndex) => {
            const startIndex = data.pages
              .slice(0, pageIndex)
              .reduce((sum, p) => sum + p.Items.length, 0);
            return {
              ...page,
              Items: reordered.slice(startIndex, startIndex + page.Items.length),
            };
          });

          queryClient.setQueryData(key, { ...data, pages: newPages });
        }
      }

      return { snapshots };
    },
    onError: (_err, _vars, context) => {
      if (context?.snapshots) {
        for (const { queryKey, data } of context.snapshots) {
          queryClient.setQueryData(queryKey, data);
        }
      }
    },
    onSettled: () => {
      Promise.all([
        queryClient.invalidateQueries({ queryKey: ['albums'] }),
        queryClient.invalidateQueries({ queryKey: ['artists'] }),
        queryClient.invalidateQueries({ queryKey: ['tracks'] }),
      ]).catch(() => {});
    },
  });
}
