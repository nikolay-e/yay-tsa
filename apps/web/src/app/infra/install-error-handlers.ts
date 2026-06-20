import { reportError } from '@/shared/utils/error-reporter';

export function installErrorHandlers(): void {
  const win = globalThis.window;
  if (!win) return;

  win.addEventListener(
    'error',
    event => {
      const target = event.target;
      if (target && target !== win && target instanceof Element) {
        if (target.tagName === 'AUDIO' || target.tagName === 'VIDEO') return;
        const el = target as Element & { src?: string; href?: string; currentSrc?: string };
        const url = [el.currentSrc, el.src, el.href].find(Boolean) ?? '';
        reportError(new Error(`Resource load failed: ${el.tagName} ${url}`), 'resource', {
          type: 'ResourceError',
        });
        return;
      }
      if (isOpaqueScriptError(event)) {
        reportError(new Error('Script error.'), 'runtime', { type: 'OpaqueScriptError' });
        return;
      }
      const rawError: unknown = event.error;
      const runtimeError = rawError instanceof Error ? rawError : new Error(event.message);
      reportError(runtimeError, 'runtime');
    },
    true
  );

  win.addEventListener('unhandledrejection', event => {
    const reason: unknown = event.reason;
    reportError(reason, 'promise');
  });

  const swContainer = globalThis.navigator?.serviceWorker;
  if (swContainer) {
    swContainer.addEventListener('messageerror', () => {
      reportError(new Error('Service worker message deserialization error'), 'sw', {
        type: 'ServiceWorkerMessageError',
      });
    });
  }
}

function isOpaqueScriptError(event: ErrorEvent): boolean {
  return !event.error && event.message === 'Script error.' && !event.filename;
}
