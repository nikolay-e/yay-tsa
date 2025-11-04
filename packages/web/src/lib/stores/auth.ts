/**
 * Authentication store
 * Manages user authentication state and session
 */

import { writable, derived, get } from 'svelte/store';
import { dev } from '$app/environment';
import {
  JellyfinClient,
  AuthService,
  validateServerUrl,
  getOrCreateDeviceId,
  STORAGE_KEYS,
  APP_VERSION,
  DEFAULT_CLIENT_NAME,
  DEFAULT_DEVICE_NAME,
  type ClientInfo,
} from '@jellyfin-mini/core';
import { config } from './config.js';

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
 */
async function login(serverUrl: string, username: string, password: string): Promise<void> {
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

    // Authenticate
    const response = await authService.login(username, password);

    // Store in sessionStorage
    if (typeof sessionStorage !== 'undefined') {
      sessionStorage.setItem(STORAGE_KEYS.SESSION, response.AccessToken);
      sessionStorage.setItem(STORAGE_KEYS.USER_ID, response.User.Id);
      sessionStorage.setItem(STORAGE_KEYS.SERVER_URL, serverUrl);
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
    console.error('Login error:', error);
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
 */
async function logout(): Promise<void> {
  const state = get(authStore);

  if (state.authService && state.client) {
    try {
      await state.authService.logout();
    } catch (error) {
      console.error('Logout error:', error);
    }
  }

  // Clear sessionStorage
  if (typeof sessionStorage !== 'undefined') {
    sessionStorage.removeItem(STORAGE_KEYS.SESSION);
    sessionStorage.removeItem(STORAGE_KEYS.USER_ID);
    sessionStorage.removeItem(STORAGE_KEYS.SERVER_URL);
  }

  // SECURITY: Clear service worker cache on logout to prevent data leakage
  if ('serviceWorker' in navigator && navigator.serviceWorker.controller) {
    try {
      navigator.serviceWorker.controller.postMessage({
        type: 'CLEAR_CACHE',
      });
    } catch (error) {
      if (dev) console.error('Failed to clear service worker cache:', error);
    }
  }

  authStore.set(initialState);
}

/**
 * Try to restore session from sessionStorage
 */
async function restoreSession(): Promise<boolean> {
  if (typeof sessionStorage === 'undefined') {
    return false;
  }

  const token = sessionStorage.getItem(STORAGE_KEYS.SESSION);
  const userId = sessionStorage.getItem(STORAGE_KEYS.USER_ID);
  // Prioritize URL saved in session over any build-time default
  const serverUrl = sessionStorage.getItem(STORAGE_KEYS.SERVER_URL);

  if (!token || !userId || !serverUrl) {
    return false;
  }

  // Validate server URL from session storage (prevent C-SSRF)
  const isDevelopment = import.meta.env.DEV || import.meta.env.MODE === 'development';
  try {
    validateServerUrl(serverUrl, isDevelopment);
  } catch {
    // Invalid URL in session storage - clear session and return false
    sessionStorage.removeItem(STORAGE_KEYS.SESSION);
    sessionStorage.removeItem(STORAGE_KEYS.USER_ID);
    sessionStorage.removeItem(STORAGE_KEYS.SERVER_URL);
    return false;
  }

  authStore.update(state => ({ ...state, isLoading: true }));

  try {
    // Create client info from config
    const clientInfo: ClientInfo = createClientInfoFromConfig();

    const client = new JellyfinClient(serverUrl, clientInfo);
    client.setToken(token, userId);

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
    console.error('Session restore error:', error);
    // Clear invalid session completely
    sessionStorage.removeItem(STORAGE_KEYS.SESSION);
    sessionStorage.removeItem(STORAGE_KEYS.USER_ID);
    sessionStorage.removeItem(STORAGE_KEYS.SERVER_URL);

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
