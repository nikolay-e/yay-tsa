import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { QueryClientProvider } from '@tanstack/react-query';
import { BeaconErrorTransport } from '@yay-tsa/platform';
// eslint-disable-next-line import/no-unresolved -- vite-plugin-pwa virtual module, resolved at build time
import { registerSW } from 'virtual:pwa-register';
import { initErrorReporter } from '@/shared/utils/error-reporter';
import { App } from './app/App';
import { ErrorBoundary } from './app/infra/ErrorBoundary';
import { ToastContainer } from './shared/ui/Toast';
import { UpdatePrompt, useUpdatePromptStore } from './shared/components/UpdatePrompt';
import { queryClient } from './shared/lib/query-client';
import { installPerf, mark } from './shared/perf/perf';
import { installErrorHandlers } from './app/infra/install-error-handlers';
import './index.css';

mark('app_start');
installPerf();

initErrorReporter(new BeaconErrorTransport('/api/v1/client-errors'));
installErrorHandlers();

const SW_UPDATE_POLL_INTERVAL_MS = 60 * 60 * 1000;

// Prompt-driven updates: the new SW stays waiting until the user accepts the
// in-app prompt, so playback is never interrupted by a silent reload. Periodic
// registration.update() keeps long-lived installed PWAs polling for new builds.
const updateServiceWorker = registerSW({
  immediate: true,
  onNeedRefresh: () => {
    useUpdatePromptStore.getState().offerUpdate(() => {
      void updateServiceWorker(true);
    });
  },
  onRegisteredSW: (_swUrl, registration) => {
    if (!registration) return;
    setInterval(() => {
      registration.update().catch(() => {});
    }, SW_UPDATE_POLL_INTERVAL_MS);
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
