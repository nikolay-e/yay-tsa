/**
 * Authentication store
 * Manages user authentication state and session
 */

import { writable, derived, get } from 'svelte/store';
import {
  JellyfinClient,
  AuthService,
  validateServerUrl,
  getOrCreateDeviceId,
  APP_VERSION,
  DEFAULT_CLIENT_NAME,
  DEFAULT_DEVICE_NAME,
  type ClientInfo,
} from '@yaytsa/core';
import { config } from './config.js';
import {
  saveSession,
  saveSessionPersistent,
  loadSessionAuto,
  clearAllSessions,
} from '../utils/session-manager.js';
import { logger } from '../utils/logger.js';
import { cacheManager } from '../cache/cache-manager.js';

const VOLUME_STORAGE_KEY = 'yaytsa_volume';

interface AuthState {
  client: JellyfinClient | null;
  authService: AuthService | null;
  token: string | null;
  userId: string | null;
  serverUrl: string | null;
  isAuthenticated: boolean;
  isLoading: boolean;
  error: string | null;
}

const initialState: AuthState = {
  client: null,
  authService: null,
  token: null,
  userId: null,
  serverUrl: null,
  isAuthenticated: false,
  isLoading: false,
  error: null,
};

// Main auth store
const authStore = writable<AuthState>(initialState);

/**
 * Login options interface
 */
export interface LoginOptions {
  rememberMe?: boolean; // If true, save to localStorage (persistent across browser restarts)
}

/**
 * Create ClientInfo from configuration store
 */
function createClientInfoFromConfig(): ClientInfo {
  const appConfig = get(config);
  const clientName = appConfig.clientName || DEFAULT_CLIENT_NAME;

  // Detect device type from user agent
  const isMobile = typeof navigator !== 'undefined' && navigator.userAgent.includes('Mobile');
  const deviceName = appConfig.deviceName || (isMobile ? 'Mobile Browser' : DEFAULT_DEVICE_NAME);

  return {
    name: clientName,
    device: deviceName,
    deviceId: getOrCreateDeviceId(),
    version: APP_VERSION,
  };
}

/**
 * Login with username and password
 * @param options - Login options (e.g., rememberMe)
 */
async function login(
  serverUrl: string,
  username: string,
  password: string,
  options?: LoginOptions
): Promise<void> {
  authStore.update(state => ({ ...state, isLoading: true, error: null }));

  try {
    // Validate server URL (check if in development mode)
    const isDevelopment = import.meta.env.DEV || import.meta.env.MODE === 'development';
    validateServerUrl(serverUrl, isDevelopment);

    // Create client info from config
    const clientInfo: ClientInfo = createClientInfoFromConfig();

    // Create client and auth service
    const client = new JellyfinClient(serverUrl, clientInfo);
    const authService = new AuthService(client);

    // Set up global 401 interceptor for auto-logout
    client.setAuthErrorCallback(() => {
      void logout(); // Auto-logout on 401/403
    });

    // Authenticate
    const response = await authService.login(username, password);

    const sessionData = {
      token: response.AccessToken,
      userId: response.User.Id,
      serverUrl,
    };

    // Save session based on rememberMe option
    if (options?.rememberMe) {
      // Persistent storage (localStorage) - survives browser close
      saveSessionPersistent(sessionData);
    } else {
      // Session storage (default) - cleared when tab closes
      saveSession(sessionData);
    }

    authStore.set({
      client,
      authService,
      token: response.AccessToken,
      userId: response.User.Id,
      serverUrl,
      isAuthenticated: true,
      isLoading: false,
      error: null,
    });
  } catch (error) {
    logger.error('Login error:', error);
    authStore.update(state => ({
      ...state,
      isLoading: false,
      error: (error as Error).message,
    }));
    throw error;
  }
}

/**
 * Logout and clear session
 * Clears both sessionStorage AND localStorage (persistent storage)
 */
async function logout(): Promise<void> {
  const state = get(authStore);

  if (state.authService && state.client) {
    try {
      await state.authService.logout();
    } catch (error) {
      logger.error('Logout error:', error);
    }
  }

  // Clear ALL session data (sessionStorage + localStorage)
  clearAllSessions();

  // Clear user preferences that should not persist between users
  try {
    localStorage.removeItem(VOLUME_STORAGE_KEY);
    logger.info('[Auth] Cleared user preferences');
  } catch (error) {
    logger.error('[Auth] Failed to clear user preferences:', error);
  }

  // Cleanup player resources (dispose timers) - sync import to avoid race conditions
  try {
    const playerModule = await import('./player.js');
    playerModule.player.cleanup();
  } catch (error) {
    logger.error('[Player] Failed to cleanup player:', error);
  }

  // SECURITY: Clear IndexedDB cache with retry logic
  const clearIndexedDB = async (retries = 1): Promise<void> => {
    try {
      await cacheManager.clearAll();
      logger.info('[Cache] Cleared IndexedDB cache on logout');
    } catch (error) {
      if (retries > 0) {
        logger.warn('[Cache] Retrying IndexedDB clear...');
        await clearIndexedDB(retries - 1);
      } else {
        throw error;
      }
    }
  };

  try {
    await clearIndexedDB();
  } catch (error) {
    logger.error('[Cache] Failed to clear IndexedDB cache on logout:', error);
  }

  // SECURITY: Clear Service Worker caches on logout to prevent data leakage
  // User-specific images and audio should not persist after logout
  if ('caches' in window) {
    try {
      const cacheNames = ['yaytsa-images-v0.3.10', 'yaytsa-audio-v0.3.10'];
      const results = await Promise.allSettled(cacheNames.map(async name => caches.delete(name)));

      const cleared = results.filter(r => r.status === 'fulfilled' && r.value).length;
      const failed = results.filter(r => r.status === 'rejected').length;

      if (cleared > 0) {
        logger.info(`[SW Cache] Cleared ${cleared} cache(s) on logout`);
      }
      if (failed > 0) {
        logger.warn(`[SW Cache] Failed to clear ${failed} cache(s)`);
      }
    } catch (error) {
      logger.error('[SW Cache] Failed to clear SW caches on logout:', error);
    }
  }

  authStore.set(initialState);
}

/**
 * Try to restore session from persistent storage (localStorage) or sessionStorage
 * Checks localStorage first (if "Remember Me" was enabled), then falls back to sessionStorage
 */
async function restoreSession(): Promise<boolean> {
  const session = loadSessionAuto();

  if (!session) {
    return false;
  }

  const { token, userId, serverUrl } = session;

  // Validate server URL from storage (prevent C-SSRF)
  const isDevelopment = import.meta.env.DEV || import.meta.env.MODE === 'development';
  try {
    validateServerUrl(serverUrl, isDevelopment);
  } catch {
    // Invalid URL in storage - clear ALL sessions and return false
    clearAllSessions();
    return false;
  }

  authStore.update(state => ({ ...state, isLoading: true }));

  try {
    // Create client info from config
    const clientInfo: ClientInfo = createClientInfoFromConfig();

    const client = new JellyfinClient(serverUrl, clientInfo);
    client.setToken(token, userId);

    // Set up global 401 interceptor for auto-logout
    client.setAuthErrorCallback(() => {
      void logout(); // Auto-logout on 401/403
    });

    // Validate session by making a test request
    await client.getServerInfo();

    const authService = new AuthService(client);

    authStore.set({
      client,
      authService,
      token,
      userId,
      serverUrl,
      isAuthenticated: true,
      isLoading: false,
      error: null,
    });

    return true;
  } catch (error) {
    logger.error('Session restore error:', error);
    // Clear invalid session completely (both sessionStorage and localStorage)
    clearAllSessions();

    authStore.update(state => ({
      ...state,
      isLoading: false,
      error: 'Session expired or invalid',
    }));

    return false;
  }
}

// Derived stores
export const isAuthenticated = derived(authStore, $auth => $auth.isAuthenticated);
export const client = derived(authStore, $auth => $auth.client);
export const isLoading = derived(authStore, $auth => $auth.isLoading);
export const error = derived(authStore, $auth => $auth.error);

export const auth = {
  subscribe: authStore.subscribe,
  login,
  logout,
  restoreSession,
};
