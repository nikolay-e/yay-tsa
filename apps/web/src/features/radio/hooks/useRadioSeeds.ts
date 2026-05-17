import { useQuery } from '@tanstack/react-query';
import { AdaptiveDjService } from '@yay-tsa/core';
import { useAuthStore } from '@/features/auth/stores/auth.store';

export function useRadioSeeds() {
  const client = useAuthStore(s => s.client);
  return useQuery({
    queryKey: ['radio', 'seeds'],
    queryFn: async () => {
      if (!client) return null;
      const service = new AdaptiveDjService(client);
      return service.getRadioSeeds();
    },
    staleTime: 0,
    gcTime: 0,
    enabled: !!client,
  });
}
