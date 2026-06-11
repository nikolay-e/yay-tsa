import { useQuery } from '@tanstack/react-query';
import { AdaptiveDjService, type AudioItem } from '@yay-tsa/core';
import { useAuthStore } from '@/features/auth/stores/auth.store';

const DEFAULT_LIMIT = 30;

export const DAILY_MIX_QUERY_KEY = ['recommend', 'daily-mix'] as const;

export function useDailyMix(limit = DEFAULT_LIMIT) {
  const client = useAuthStore(s => s.client);
  return useQuery<AudioItem[]>({
    queryKey: [...DAILY_MIX_QUERY_KEY, limit],
    queryFn: async () => {
      if (!client) return [];
      const service = new AdaptiveDjService(client);
      return service.getDailyMix(limit);
    },
    staleTime: 60 * 60 * 1000,
    gcTime: 60 * 60 * 1000,
    enabled: !!client,
  });
}
