/**
 * Shared application constants
 */

/**
 * Application metadata
 */
export const APP_VERSION = '0.3.4';
export const DEFAULT_CLIENT_NAME = 'Yaytsa';
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

/**
 * Jellyfin ticks conversion
 * Jellyfin uses ticks (10,000,000 ticks per second) for time positions
 */
export const TICKS_PER_SECOND = 10_000_000;

/**
 * Convert seconds to Jellyfin ticks
 */
export function secondsToTicks(seconds: number): number {
  return Math.floor(seconds * TICKS_PER_SECOND);
}

/**
 * Convert Jellyfin ticks to seconds
 */
export function ticksToSeconds(ticks: number): number {
  return ticks / TICKS_PER_SECOND;
}
