import { QueryCache, QueryClient } from '@tanstack/react-query';
import { reportError } from '@/shared/utils/error-reporter';
import { toast } from '@/shared/ui/Toast';

const NETWORK_TOAST_COOLDOWN_MS = 5000;
let lastNetworkToastAt = 0;

function surfaceReadFailure(): void {
  const now = Date.now();
  if (now - lastNetworkToastAt > NETWORK_TOAST_COOLDOWN_MS) {
    lastNetworkToastAt = now;
    toast.add('error', 'Could not load — check your connection and try again.');
  }
}

export const queryClient = new QueryClient({
  queryCache: new QueryCache({
    onError: (error, query) => {
      reportError(error, 'network', {
        type: error instanceof Error ? error.name : undefined,
        route: typeof query.queryKey[0] === 'string' ? query.queryKey[0] : undefined,
      });
      surfaceReadFailure();
    },
  }),
  defaultOptions: {
    queries: {
      staleTime: 5 * 60 * 1000,
      gcTime: 10 * 60 * 1000,
      retry: false,
      refetchOnWindowFocus: true,
      refetchOnReconnect: true,
    },
  },
});
