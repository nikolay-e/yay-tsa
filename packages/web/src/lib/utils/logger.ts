/**
 * Centralized logging utility
 * Automatically disabled in production builds
 */

import { dev } from '$app/environment';

export const logger = {
  info: (message: string, ...args: unknown[]): void => {
    if (dev) {
      console.info(`[App] ${message}`, ...args);
    }
  },

  warn: (message: string, ...args: unknown[]): void => {
    if (dev) {
      console.warn(`[App] ${message}`, ...args);
    }
  },

  error: (message: string, ...args: unknown[]): void => {
    if (dev) {
      console.error(`[App] ${message}`, ...args);
    }
  },

  debug: (message: string, ...args: unknown[]): void => {
    if (dev) {
      console.debug(`[App] ${message}`, ...args);
    }
  },
};
