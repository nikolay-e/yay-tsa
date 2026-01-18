type LogLevel = 'debug' | 'info' | 'warn' | 'error' | 'silent';

interface LogContext {
  [key: string]: unknown;
}

interface Logger {
  debug(message: string, context?: LogContext): void;
  info(message: string, context?: LogContext): void;
  warn(message: string, context?: LogContext): void;
  error(message: string, error?: Error | unknown, context?: LogContext): void;
}

declare global {
  interface Window {
    __YAYTSA_CONFIG__?: {
      serverUrl?: string;
      clientName?: string;
      deviceName?: string;
      version?: string;
      logLevel?: LogLevel;
    };
  }
}

const LOG_LEVEL_PRIORITY: Record<LogLevel, number> = {
  debug: 0,
  info: 1,
  warn: 2,
  error: 3,
  silent: 4,
};

const VALID_LOG_LEVELS: LogLevel[] = ['debug', 'info', 'warn', 'error', 'silent'];

function isValidLogLevel(level: unknown): level is LogLevel {
  return typeof level === 'string' && VALID_LOG_LEVELS.includes(level as LogLevel);
}

function detectEnvironment(): { isDev: boolean; logLevel: LogLevel } {
  // Priority 1: Runtime config (injected by Docker entrypoint in production)
  if (typeof window !== 'undefined' && window.__YAYTSA_CONFIG__?.logLevel) {
    const runtimeLevel = window.__YAYTSA_CONFIG__.logLevel;
    if (isValidLogLevel(runtimeLevel)) {
      return { isDev: runtimeLevel === 'debug', logLevel: runtimeLevel };
    }
  }

  // Priority 2: Vite environment (browser or SSR)
  if (import.meta?.env) {
    const env = import.meta.env as Record<string, unknown>;
    const isDev = env.DEV === true || env.MODE === 'development';
    const envLevel: unknown = env.VITE_LOG_LEVEL;
    if (isValidLogLevel(envLevel)) {
      return { isDev, logLevel: envLevel };
    }
    return { isDev, logLevel: isDev ? 'debug' : 'error' };
  }

  // Priority 3: Node.js environment
  if (typeof process !== 'undefined' && process.env) {
    const isDev = process.env.NODE_ENV !== 'production';
    const envLevel = process.env.LOG_LEVEL;
    if (isValidLogLevel(envLevel)) {
      return { isDev, logLevel: envLevel };
    }
    return { isDev, logLevel: isDev ? 'debug' : 'error' };
  }

  // Fallback: assume production
  return { isDev: false, logLevel: 'error' };
}

const environment = detectEnvironment();

function shouldLog(level: LogLevel): boolean {
  const configuredLevel = environment.logLevel;
  return LOG_LEVEL_PRIORITY[level] >= LOG_LEVEL_PRIORITY[configuredLevel];
}

function sanitizeValue(value: unknown): unknown {
  if (typeof value === 'string') {
    return value
      .replace(/api_key=[^&\s]+/gi, 'api_key=[REDACTED]')
      .replace(/token=[^&\s]+/gi, 'token=[REDACTED]')
      .replace(/password=[^&\s]+/gi, 'password=[REDACTED]');
  }
  return value;
}

function sanitizeContext(context?: LogContext): LogContext | undefined {
  if (!context) return undefined;

  const sanitized: LogContext = {};
  for (const [key, value] of Object.entries(context)) {
    sanitized[key] = sanitizeValue(value);
  }
  return sanitized;
}

function formatMessage(namespace: string, message: string): string {
  return `[${namespace}] ${message}`;
}

function formatErrorMessage(error: Error | unknown): string {
  if (error instanceof Error) {
    return sanitizeValue(error.message) as string;
  }
  if (typeof error === 'string') {
    return sanitizeValue(error) as string;
  }
  return String(error);
}

export function createLogger(namespace: string): Logger {
  return {
    debug(message: string, context?: LogContext): void {
      if (shouldLog('debug')) {
        const formatted = formatMessage(namespace, message);
        if (context) {
          // eslint-disable-next-line no-console
          console.debug(formatted, sanitizeContext(context));
        } else {
          // eslint-disable-next-line no-console
          console.debug(formatted);
        }
      }
    },

    info(message: string, context?: LogContext): void {
      if (shouldLog('info')) {
        const formatted = formatMessage(namespace, message);
        if (context) {
          console.info(formatted, sanitizeContext(context));
        } else {
          console.info(formatted);
        }
      }
    },

    warn(message: string, context?: LogContext): void {
      if (shouldLog('warn')) {
        const formatted = formatMessage(namespace, message);
        if (context) {
          console.warn(formatted, sanitizeContext(context));
        } else {
          console.warn(formatted);
        }
      }
    },

    error(message: string, error?: Error | unknown, context?: LogContext): void {
      if (shouldLog('error')) {
        const formatted = formatMessage(namespace, message);
        const errorMsg = error ? formatErrorMessage(error) : undefined;

        if (errorMsg && context) {
          console.error(formatted, errorMsg, sanitizeContext(context));
        } else if (errorMsg) {
          console.error(formatted, errorMsg);
        } else if (context) {
          console.error(formatted, sanitizeContext(context));
        } else {
          console.error(formatted);
        }
      }
    },
  };
}

export const logger = createLogger('Core');

export type { Logger, LogContext, LogLevel };
