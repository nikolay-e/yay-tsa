/**
 * Session Storage Manager
 * Centralized utility for managing session storage operations
 */

import { STORAGE_KEYS } from '@jellyfin-mini/core';

export interface SessionData {
  token: string;
  userId: string;
  serverUrl: string;
}

/**
 * Save session data to sessionStorage
 */
export function saveSession(data: SessionData): void {
  if (typeof sessionStorage === 'undefined') {
    return;
  }

  sessionStorage.setItem(STORAGE_KEYS.SESSION, data.token);
  sessionStorage.setItem(STORAGE_KEYS.USER_ID, data.userId);
  sessionStorage.setItem(STORAGE_KEYS.SERVER_URL, data.serverUrl);
}

/**
 * Load session data from sessionStorage
 * Returns null if any required field is missing or sessionStorage is unavailable
 */
export function loadSession(): SessionData | null {
  if (typeof sessionStorage === 'undefined') {
    return null;
  }

  const token = sessionStorage.getItem(STORAGE_KEYS.SESSION);
  const userId = sessionStorage.getItem(STORAGE_KEYS.USER_ID);
  const serverUrl = sessionStorage.getItem(STORAGE_KEYS.SERVER_URL);

  if (!token || !userId || !serverUrl) {
    return null;
  }

  return { token, userId, serverUrl };
}

/**
 * Clear all session data from sessionStorage
 */
export function clearSession(): void {
  if (typeof sessionStorage === 'undefined') {
    return;
  }

  sessionStorage.removeItem(STORAGE_KEYS.SESSION);
  sessionStorage.removeItem(STORAGE_KEYS.USER_ID);
  sessionStorage.removeItem(STORAGE_KEYS.SERVER_URL);
}
