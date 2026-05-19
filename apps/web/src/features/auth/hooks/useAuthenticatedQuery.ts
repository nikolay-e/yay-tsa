import { useQuery, type QueryKey, type UseQueryOptions } from '@tanstack/react-query';
import { type MediaServerClient } from '@yay-tsa/core';
import { useAuthStore } from '@/features/auth/stores/auth.store';

export function useAuthenticatedQuery<TData, TError = Error>(
  key: QueryKey,
  fetcher: (client: MediaServerClient) => Promise<TData>,
  options?: Omit<UseQueryOptions<TData, TError>, 'queryKey' | 'queryFn'>
) {
  const client = useAuthStore(state => state.client);
  return useQuery<TData, TError>({
    ...options,
    queryKey: key,
    queryFn: async () => {
      if (!client) throw new Error('Not authenticated');
      return fetcher(client);
    },
    enabled: !!client && (options?.enabled ?? true),
  });
}
