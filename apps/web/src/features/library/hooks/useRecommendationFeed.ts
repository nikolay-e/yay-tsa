import { useQuery } from '@tanstack/react-query';
import { AdaptiveDjService, type AudioItem } from '@yay-tsa/core';
import { useAuthStore } from '@/features/auth/stores/auth.store';

const RECOMMENDATION_DEFAULT_LIMIT = 30;

export function useRecommendationFeed(
  queryKey: readonly string[],
  fetch: (service: AdaptiveDjService, limit: number) => Promise<AudioItem[]>,
  limit = RECOMMENDATION_DEFAULT_LIMIT
) {
  const client = useAuthStore(s => s.client);
  return useQuery<AudioItem[]>({
    queryKey: [...queryKey, limit],
    queryFn: async () => {
      if (!client) return [];
      return fetch(new AdaptiveDjService(client), limit);
    },
    staleTime: 60 * 60 * 1000,
    gcTime: 60 * 60 * 1000,
    enabled: !!client,
  });
}
