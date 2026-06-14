import { useQuery } from '@tanstack/react-query';
import { AdaptiveDjService, type AudioItem } from '@yay-tsa/core';
import { useAuthStore } from '@/features/auth/stores/auth.store';

const DEFAULT_LIMIT = 30;

export const DISCOVER_QUERY_KEY = ['recommend', 'discover'] as const;

export function useDiscover(limit = DEFAULT_LIMIT) {
  const client = useAuthStore(s => s.client);
  return useQuery<AudioItem[]>({
    queryKey: [...DISCOVER_QUERY_KEY, limit],
    queryFn: async () => {
      if (!client) return [];
      const service = new AdaptiveDjService(client);
      return service.getDiscover(limit);
    },
    staleTime: 60 * 60 * 1000,
    gcTime: 60 * 60 * 1000,
    enabled: !!client,
  });
}
