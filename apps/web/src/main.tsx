import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { QueryClientProvider } from '@tanstack/react-query';
import { setLogSink, setRuntimeProviders } from '@yay-tsa/core';
import {
  browserKeyValueStorage,
  browserRuntimeConfigSource,
  browserVisibilitySignal,
} from '@yay-tsa/platform';
// eslint-disable-next-line import/no-unresolved -- vite-plugin-pwa virtual module, resolved at build time
import { registerSW } from 'virtual:pwa-register';
import {
  initErrorReporter,
  reportError,
  type ClientErrorCategory,
} from '@/shared/utils/error-reporter';
import { App } from './app/App';
import { ErrorBoundary } from './app/infra/ErrorBoundary';
import { installChunkReloadRecovery } from './app/infra/chunk-reload';
import { ToastContainer } from './shared/ui/Toast';
import { UpdatePrompt, useUpdatePromptStore } from './shared/components/UpdatePrompt';
import { queryClient } from './shared/lib/query-client';
import { installPerf, mark } from './shared/perf/perf';
import './index.css';

mark('app_start');
setRuntimeProviders({
  storage: browserKeyValueStorage,
  runtimeConfig: browserRuntimeConfigSource,
  visibility: browserVisibilitySignal,
});
installPerf();
installChunkReloadRecovery();

// Installs all global error/rejection/resource/SW handlers via the canonical
// dependency-free @yay-tsa/platform module (the copy-out source of truth).
initErrorReporter('/api/v1/client-errors');

function categoryForNamespace(namespace: string): ClientErrorCategory {
  switch (namespace.toLowerCase()) {
    case 'auth':
      return 'auth';
    case 'player':
      return 'playback';
    case 'api':
      return 'network';
    case 'app':
      return 'react';
    case 'audio':
      return 'audio';
    case 'offlinestore':
      return 'offline';
    default:
      return 'other';
  }
}

function errorNameFrom(error: unknown): string | undefined {
  return error instanceof Error && error.name ? error.name : undefined;
}

setLogSink(entry => {
  const category = categoryForNamespace(entry.namespace);
  const errorText = entry.error instanceof Error ? entry.error.message : undefined;
  const message = errorText ? `${entry.message}: ${errorText}` : entry.message;
  const type = errorNameFrom(entry.error) ?? `${entry.namespace}:${entry.level}`;
  const context = entry.context;
  reportError(entry.error ?? new Error(entry.message), category, {
    message,
    type,
    stack: entry.error instanceof Error ? entry.error.stack : undefined,
    http: context?.http as { status?: number; method?: string; route?: string } | undefined,
    audio: context?.audio as
      | { state?: string; mediaError?: number | null; readyState?: number; networkState?: number }
      | undefined,
  });
});

const SW_UPDATE_POLL_INTERVAL_MS = 15 * 60 * 1000;

// Prompt-driven updates: the new SW stays waiting until the user accepts the
// in-app prompt, so playback is never interrupted by a silent reload. The
// browser never surfaces an "update this app" affordance for a PWA — the app
// must detect the waiting SW itself and show the prompt.
const updateServiceWorker = registerSW({
  immediate: true,
  onNeedRefresh: () => {
    useUpdatePromptStore.getState().offerUpdate(() => {
      void updateServiceWorker(true);
    });
  },
  onRegisteredSW: (_swUrl, registration) => {
    if (!registration) return;
    const checkForUpdate = () => {
      registration.update().catch(() => {});
    };
    // Poll on a fixed cadence for a long-lived open tab, AND check immediately
    // whenever the user returns to the tab — otherwise the "Update available"
    // prompt only surfaces up to a full poll interval after a new build ships,
    // which reads as "the update button never appears".
    setInterval(checkForUpdate, SW_UPDATE_POLL_INTERVAL_MS);
    document.addEventListener('visibilitychange', () => {
      if (document.visibilityState === 'visible') checkForUpdate();
    });
  },
});

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <ErrorBoundary>
      <QueryClientProvider client={queryClient}>
        <App />
        <ToastContainer />
        <UpdatePrompt />
      </QueryClientProvider>
    </ErrorBoundary>
  </StrictMode>
);
