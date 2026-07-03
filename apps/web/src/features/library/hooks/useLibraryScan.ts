import { useMutation, useQueryClient } from '@tanstack/react-query';
import { AdminService, ItemsService, MediaServerError } from '@yay-tsa/core';
import { useAuthStore, useIsAdmin } from '@/features/auth/stores/auth.store';
import { useAuthenticatedQuery } from '@/features/auth/hooks/useAuthenticatedQuery';
import { toast } from '@/shared/ui/Toast';

export const SCAN_STATUS_QUERY_KEY = ['admin', 'scan-status'] as const;
export const LIBRARY_TOTAL_COUNT_QUERY_KEY = ['tracks', 'total-count'] as const;

export function useScanStatus() {
  const isAdmin = useIsAdmin();
  return useAuthenticatedQuery(
    [...SCAN_STATUS_QUERY_KEY],
    async client => new AdminService(client).getScanStatus(),
    {
      enabled: isAdmin,
      refetchInterval: query => (query.state.data?.scanning ? 3000 : false),
    }
  );
}

export function useRescanLibrary() {
  const client = useAuthStore(state => state.client);
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: async () => {
      if (!client) throw new Error('Not authenticated');
      return new AdminService(client).rescanLibrary();
    },
    onSuccess: result => {
      toast.add(
        'success',
        result.status === 'already_running'
          ? 'A library scan is already running'
          : 'Library scan started'
      );
      void queryClient.invalidateQueries({ queryKey: SCAN_STATUS_QUERY_KEY });
    },
    onError: error => {
      if (error instanceof MediaServerError && error.statusCode === 409) {
        toast.add('info', 'A library scan is already running');
        void queryClient.invalidateQueries({ queryKey: SCAN_STATUS_QUERY_KEY });
        return;
      }
      toast.add('error', 'Failed to start library scan — please try again');
    },
  });
}

export function useLibraryTotalCount() {
  return useAuthenticatedQuery(
    [...LIBRARY_TOTAL_COUNT_QUERY_KEY],
    async client => new ItemsService(client).getTracks({ limit: 1 }),
    {
      staleTime: 60 * 1000,
      refetchInterval: query => (query.state.data?.TotalRecordCount === 0 ? 10_000 : false),
    }
  );
}
