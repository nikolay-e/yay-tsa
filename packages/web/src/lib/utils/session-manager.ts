/**
 * Session Storage Manager
 * Centralized utility for managing session storage operations
 */

import { STORAGE_KEYS } from '@yaytsa/core';

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

/**
 * Save session data to localStorage (persistent - survives browser close)
 * Used when "Remember Me" is enabled
 */
export function saveSessionPersistent(data: SessionData): void {
  if (typeof localStorage === 'undefined') {
    return;
  }

  localStorage.setItem(STORAGE_KEYS.SESSION_PERSISTENT, data.token);
  localStorage.setItem(STORAGE_KEYS.USER_ID_PERSISTENT, data.userId);
  localStorage.setItem(STORAGE_KEYS.SERVER_URL_PERSISTENT, data.serverUrl);
  localStorage.setItem(STORAGE_KEYS.REMEMBER_ME, 'true');
}

/**
 * Load session data from localStorage (persistent)
 * Returns null if any required field is missing or localStorage is unavailable
 */
export function loadSessionPersistent(): SessionData | null {
  if (typeof localStorage === 'undefined') {
    return null;
  }

  const rememberMe = localStorage.getItem(STORAGE_KEYS.REMEMBER_ME);
  if (rememberMe !== 'true') {
    return null; // "Remember Me" was not enabled
  }

  const token = localStorage.getItem(STORAGE_KEYS.SESSION_PERSISTENT);
  const userId = localStorage.getItem(STORAGE_KEYS.USER_ID_PERSISTENT);
  const serverUrl = localStorage.getItem(STORAGE_KEYS.SERVER_URL_PERSISTENT);

  if (!token || !userId || !serverUrl) {
    return null;
  }

  return { token, userId, serverUrl };
}

/**
 * Clear all persistent session data from localStorage
 */
export function clearSessionPersistent(): void {
  if (typeof localStorage === 'undefined') {
    return;
  }

  localStorage.removeItem(STORAGE_KEYS.SESSION_PERSISTENT);
  localStorage.removeItem(STORAGE_KEYS.USER_ID_PERSISTENT);
  localStorage.removeItem(STORAGE_KEYS.SERVER_URL_PERSISTENT);
  localStorage.removeItem(STORAGE_KEYS.REMEMBER_ME);
}

/**
 * Automatically load session from either localStorage or sessionStorage
 * Priority: localStorage (if "Remember Me" enabled) > sessionStorage
 * Returns null if no valid session found
 */
export function loadSessionAuto(): SessionData | null {
  // Try persistent storage first (localStorage)
  const persistentSession = loadSessionPersistent();
  if (persistentSession) {
    return persistentSession;
  }

  // Fallback to session storage
  return loadSession();
}

/**
 * Clear all session data from both sessionStorage and localStorage
 */
export function clearAllSessions(): void {
  clearSession();
  clearSessionPersistent();
}
