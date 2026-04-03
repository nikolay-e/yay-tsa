import { useQuery, keepPreviousData } from '@tanstack/react-query';
import {
  AdaptiveDjService,
  ItemsService,
  type AudioItem,
  type RecommendedTrack,
} from '@yay-tsa/core';
import { useAuthStore } from '@/features/auth/stores/auth.store';

export function useSemanticSearch(query: string, enabled: boolean) {
  const client = useAuthStore(state => state.client);

  return useQuery<AudioItem[]>({
    queryKey: ['semantic-search', query],
    queryFn: async () => {
      if (!client || !query.trim()) return [];

      const djService = new AdaptiveDjService(client);
      const results: RecommendedTrack[] = await djService.searchByText(query.trim(), 50);
      if (results.length === 0) return [];

      const itemsService = new ItemsService(client);
      return itemsService.getItemsByIds(results.map(r => r.trackId));
    },
    enabled: enabled && !!client && query.trim().length > 0,
    placeholderData: keepPreviousData,
    staleTime: 60_000,
  });
}
