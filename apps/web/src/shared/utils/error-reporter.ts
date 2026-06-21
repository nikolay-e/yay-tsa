import { redactSecrets } from '@yay-tsa/core';
import type { ClientErrorCategory, ClientErrorReport, ErrorTransport } from '@yay-tsa/platform';

const APP_VERSION = (import.meta.env.VITE_APP_VERSION as string | undefined) ?? 'dev';

const MAX_DISTINCT_PER_SESSION = 25;
const MAX_MESSAGE_LENGTH = 1024;
const MAX_STACK_LENGTH = 4096;
const MAX_TYPE_LENGTH = 100;

interface CaptureExtra {
  type?: string;
  message?: string;
  route?: string;
  stack?: string;
  http?: ClientErrorReport['http'];
  audio?: ClientErrorReport['audio'];
}

export class ErrorReporter {
  private readonly sessionId = newSessionId();
  private readonly seen = new Set<string>();
  private route: string | undefined;
  private capturing = false;

  constructor(private readonly transport: ErrorTransport) {}

  setRoute(route: string): void {
    this.route = route;
  }

  capture(error: unknown, category: ClientErrorCategory, extra: CaptureExtra = {}): void {
    if (this.capturing) return;
    this.capturing = true;
    try {
      const type = truncate(extra.type ?? errorName(error), MAX_TYPE_LENGTH);
      const message = redactSecrets(
        truncate(extra.message ?? errorMessage(error), MAX_MESSAGE_LENGTH)
      );
      const fingerprint = fingerprintOf(category, type, message);

      if (this.seen.has(fingerprint)) return;
      if (this.seen.size >= MAX_DISTINCT_PER_SESSION) return;
      this.seen.add(fingerprint);

      const stack = extra.stack ?? errorStack(error);
      const report: ClientErrorReport = {
        category,
        type,
        message,
        appVersion: APP_VERSION,
        telemetrySessionId: this.sessionId,
        fingerprint,
        route: extra.route ?? this.route,
        stack: stack ? redactSecrets(truncate(stack, MAX_STACK_LENGTH)) : undefined,
        uaReduced: reduceUserAgent(globalThis.navigator?.userAgent),
        http: extra.http,
        audio: extra.audio,
      };
      this.transport.send(report);
    } catch {
      /* the reporter must never throw */
    } finally {
      this.capturing = false;
    }
  }
}

let instance: ErrorReporter | null = null;

export function initErrorReporter(transport: ErrorTransport): ErrorReporter {
  instance = new ErrorReporter(transport);
  return instance;
}

export function reportError(
  error: unknown,
  category: ClientErrorCategory,
  extra?: CaptureExtra
): void {
  instance?.capture(error, category, extra);
}

export function setReporterRoute(route: string): void {
  instance?.setRoute(route);
}

function newSessionId(): string {
  const uuid = globalThis.crypto?.randomUUID?.();
  if (uuid) return uuid;
  return `sess-${Date.now().toString(36)}-${Math.floor(Math.random() * 1e9).toString(36)}`;
}

function errorName(error: unknown): string {
  if (error instanceof Error && error.name) return error.name;
  return 'Error';
}

function errorMessage(error: unknown): string {
  if (error instanceof Error) return error.message;
  if (typeof error === 'string') return error;
  try {
    return String(error);
  } catch {
    return 'Unknown error';
  }
}

function errorStack(error: unknown): string | undefined {
  return error instanceof Error ? error.stack : undefined;
}

function truncate(value: string, max: number): string {
  return value.length > max ? `${value.slice(0, max)}…` : value;
}

const BROWSER_PATTERNS: ReadonlyArray<readonly [RegExp, string]> = [
  [/edg\//i, 'Edge'],
  [/chrome\//i, 'Chrome'],
  [/firefox\//i, 'Firefox'],
  [/safari\//i, 'Safari'],
];

const OS_PATTERNS: ReadonlyArray<readonly [RegExp, string]> = [
  [/android/i, 'Android'],
  [/iphone|ipad|ipod/i, 'iOS'],
  [/windows/i, 'Windows'],
  [/mac os/i, 'macOS'],
  [/linux/i, 'Linux'],
];

function matchLabel(value: string, patterns: ReadonlyArray<readonly [RegExp, string]>): string {
  for (const [pattern, label] of patterns) {
    if (pattern.test(value)) return label;
  }
  return 'Other';
}

function reduceUserAgent(ua: string | undefined): string | undefined {
  if (!ua) return undefined;
  return `${matchLabel(ua, BROWSER_PATTERNS)} / ${matchLabel(ua, OS_PATTERNS)}`;
}

function fingerprintOf(category: string, type: string, message: string): string {
  const normalized = message
    .toLowerCase()
    .replace(/https?:\/\/\S+/g, '<url>')
    .replace(/\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\b/gi, '<id>')
    .replace(/\b(?=[0-9a-f-]*[0-9])[0-9a-f]{8,}\b/gi, '<id>')
    .replace(/\d+/g, '<n>')
    .replace(/\s+/g, ' ')
    .trim();
  return `${category}|${type}|${hash(normalized)}`;
}

function hash(input: string): string {
  let h = 5381;
  for (let i = 0; i < input.length; i++) {
    h = (h * 33) ^ (input.codePointAt(i) ?? 0);
  }
  return (h >>> 0).toString(36);
}
