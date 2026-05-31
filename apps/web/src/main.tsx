import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { QueryClientProvider } from '@tanstack/react-query';
// eslint-disable-next-line import/no-unresolved -- vite-plugin-pwa virtual module, resolved at build time
import { registerSW } from 'virtual:pwa-register';
import { App } from './app/App';
import { ErrorBoundary } from './app/infra/ErrorBoundary';
import { ToastContainer } from './shared/ui/Toast';
import { queryClient } from './shared/lib/query-client';
import './index.css';

// autoUpdate without a registration is a no-op for open tabs: the new SW activates and
// purges old content-hashed chunks while live tabs still import them -> ChunkLoadError.
// registerSW wires controllerchange -> reload so an updated build is picked up cleanly.
registerSW({ immediate: true });

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
