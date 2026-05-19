/**
 * Shared application constants
 */

/**
 * Application metadata
 */
// Placeholder - actual version injected at runtime via APP_VERSION env var
export const APP_VERSION = '0.0.0-placeholder';
export const DEFAULT_CLIENT_NAME = 'Yay-Tsa';
export const DEFAULT_DEVICE_NAME = 'Web Browser';

/**
 * Storage keys for browser storage
 */
export const STORAGE_KEYS = {
  DEVICE_ID: 'yaytsa_device_id',
  SESSION: 'yaytsa_session',
  USER_ID: 'yaytsa_user_id',
  SERVER_URL: 'yaytsa_server_url',
  // Persistent storage keys (localStorage) - used when "Remember Me" is enabled
  SESSION_PERSISTENT: 'yaytsa_session_persistent',
  USER_ID_PERSISTENT: 'yaytsa_user_id_persistent',
  SERVER_URL_PERSISTENT: 'yaytsa_server_url_persistent',
  REMEMBER_ME: 'yaytsa_remember_me', // Flag to indicate if "Remember Me" was selected
} as const;

export { TICKS_PER_MS, TICKS_PER_SECOND } from '../../generated/constants.js';

import { TICKS_PER_SECOND } from '../../generated/constants.js';

export function secondsToTicks(seconds: number): number {
  return Math.floor(seconds * TICKS_PER_SECOND);
}

export function ticksToSeconds(ticks: number): number {
  return ticks / TICKS_PER_SECOND;
}
