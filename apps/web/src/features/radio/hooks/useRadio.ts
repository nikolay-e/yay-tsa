import { useCallback } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { RadioService, type RadioFilters } from '@yay-tsa/core';
import { useAuthStore } from '@/features/auth/stores/auth.store';

export function useMyWave(filters: RadioFilters = {}, count = 20) {
  const client = useAuthStore(state => state.client);

  return useQuery({
    queryKey: ['radio', 'myWave', filters, count],
    queryFn: async () => {
      if (!client) throw new Error('Not authenticated');
      const radioService = new RadioService(client);
      return radioService.getMyWave(filters, count);
    },
    enabled: !!client,
    staleTime: 2 * 60 * 1000,
  });
}

export function useRadioFilters() {
  const client = useAuthStore(state => state.client);

  return useQuery({
    queryKey: ['radio', 'filters'],
    queryFn: async () => {
      if (!client) throw new Error('Not authenticated');
      const radioService = new RadioService(client);
      return radioService.getAvailableFilters();
    },
    enabled: !!client,
    staleTime: 10 * 60 * 1000,
  });
}

export function useAnalysisStats(enabled = true) {
  const client = useAuthStore(state => state.client);

  return useQuery({
    queryKey: ['radio', 'analysisStats'],
    queryFn: async () => {
      if (!client) throw new Error('Not authenticated');
      const radioService = new RadioService(client);
      return radioService.getAnalysisStats();
    },
    enabled: enabled && !!client,
    refetchInterval: (query) => {
      return query.state.data?.batchRunning ? 5000 : false;
    },
    staleTime: 5000,
  });
}

export function useRefreshRadio() {
  const queryClient = useQueryClient();
  return useCallback(() => {
    void queryClient.invalidateQueries({ queryKey: ['radio', 'myWave'] });
  }, [queryClient]);
}
