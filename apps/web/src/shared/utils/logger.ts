type LogLevel = 'debug' | 'info' | 'warn' | 'error';

interface LogContext {
  [key: string]: unknown;
}

const LOG_LEVELS: Record<LogLevel, number> = {
  debug: 0,
  info: 1,
  warn: 2,
  error: 3,
};

function getMinLevel(): number {
  if (import.meta.env.PROD) {
    return LOG_LEVELS.warn;
  }
  return LOG_LEVELS.debug;
}

function formatContext(context?: LogContext): string {
  if (!context || Object.keys(context).length === 0) {
    return '';
  }
  return ` ${JSON.stringify(context)}`;
}

function shouldLog(level: LogLevel): boolean {
  return LOG_LEVELS[level] >= getMinLevel();
}

function createLogger(namespace: string) {
  const prefix = `[${namespace}]`;

  return {
    debug(message: string, context?: LogContext) {
      if (shouldLog('debug')) {
        // eslint-disable-next-line no-console
        console.log('%s', `${prefix} ${message}${formatContext(context)}`);
      }
    },

    info(message: string, context?: LogContext) {
      if (shouldLog('info')) {
        console.info('%s', `${prefix} ${message}${formatContext(context)}`);
      }
    },

    warn(message: string, context?: LogContext) {
      if (shouldLog('warn')) {
        console.warn('%s', `${prefix} ${message}${formatContext(context)}`);
      }
    },

    error(message: string, error?: unknown, context?: LogContext) {
      if (shouldLog('error')) {
        const errorInfo =
          error instanceof Error
            ? { name: error.name, message: error.message, stack: error.stack }
            : error;
        console.error('%s', `${prefix} ${message}`, errorInfo, context);
      }
    },
  };
}

export const log = {
  auth: createLogger('auth'),
  player: createLogger('player'),
  api: createLogger('api'),
  app: createLogger('app'),
};
