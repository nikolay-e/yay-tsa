import {
  installClientTelemetry,
  type ClientTelemetryCategory,
  type ClientTelemetryHandle,
} from '@yay-tsa/platform';

// Thin app-side adapter over the canonical @yay-tsa/platform client-telemetry
// module. Capture/dedup/scrub/transport + global handler installation all live
// in that one dependency-free module (the copy-out source of truth); this file
// keeps the app's existing reportError/setReporterRoute call sites stable.

export type ClientErrorCategory = ClientTelemetryCategory;

interface CaptureExtra {
  type?: string;
  message?: string;
  route?: string;
  stack?: string;
  http?: { status?: number; method?: string; route?: string };
  audio?: {
    state?: string;
    mediaError?: number | null;
    readyState?: number;
    networkState?: number;
  };
}

const APP_VERSION = (import.meta.env.VITE_APP_VERSION as string | undefined) ?? 'dev';

let handle: ClientTelemetryHandle | null = null;

export function initErrorReporter(endpoint: string): ClientTelemetryHandle {
  handle = installClientTelemetry({ endpoint, appVersion: APP_VERSION });
  return handle;
}

export function reportError(
  error: unknown,
  category: ClientErrorCategory,
  extra?: CaptureExtra
): void {
  handle?.report(error, category, extra);
}

export function setReporterRoute(route: string): void {
  handle?.setRoute(route);
}
