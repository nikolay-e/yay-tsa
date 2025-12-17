export { createLogger, logger, type Logger, type LogContext, type LogLevel } from '@yaytsa/core';
import { createLogger } from '@yaytsa/core';

export const authLogger = createLogger('Auth');
export const playerLogger = createLogger('Player');
export const cacheLogger = createLogger('Cache');
export const uiLogger = createLogger('UI');
