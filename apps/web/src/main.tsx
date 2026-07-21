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
const SW_UPDATE_APPLY_RETRY_MS = 30 * 1000;

const isPlaybackActive = () =>
  Array.from(document.querySelectorAll('audio')).some(el => !el.paused && !el.ended);

// Silent auto-update: no prompt, no button. A freshly shipped build is applied
// the moment it cannot interrupt anyone — immediately when nothing is playing,
// otherwise deferred (30s recheck) until playback pauses or ends. The reload is
// the only way a PWA picks up the new bundle; the version IS the deployed git sha.
const updateServiceWorker = registerSW({
  immediate: true,
  onNeedRefresh: () => {
    const applyIfIdle = () => {
      if (isPlaybackActive()) return false;
      void updateServiceWorker(true);
      return true;
    };
    if (applyIfIdle()) return;
    const retry = setInterval(() => {
      if (applyIfIdle()) clearInterval(retry);
    }, SW_UPDATE_APPLY_RETRY_MS);
  },
  onRegisteredSW: (_swUrl, registration) => {
    if (!registration) return;
    const checkForUpdate = () => {
      registration.update().catch(() => {});
    };
    // Poll on a fixed cadence for a long-lived open tab, AND check immediately
    // whenever the user returns to the tab — otherwise a new build is picked up
    // only up to a full poll interval after it ships.
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
      </QueryClientProvider>
    </ErrorBoundary>
  </StrictMode>
);
