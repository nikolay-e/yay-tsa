import { describe, it, expect, beforeEach, vi } from 'vitest';

// Shared, hoisted mock surface so the vi.mock factory and the tests reference
// the exact same instances (incl. the AuthenticationError class used by
// `instanceof` checks inside the store).
const h = vi.hoisted(() => {
  class AuthenticationError extends Error {
    statusCode?: number;
    constructor(message: string, statusCode?: number) {
      super(message);
      this.name = 'AuthenticationError';
      this.statusCode = statusCode;
    }
  }
  return {
    AuthenticationError,
    mockGet: vi.fn(),
    mockLogin: vi.fn(),
    mockLogout: vi.fn().mockResolvedValue(undefined),
    authErrorCallbacks: [] as Array<() => void>,
  };
});

vi.mock('@yay-tsa/core', () => {
  class MediaServerClient {
    setToken = vi.fn();
    clearToken = vi.fn();
    get = h.mockGet;
    post = vi.fn().mockResolvedValue(undefined);
    isAuthenticated = () => true;
    getUserId = () => null;
    getClientInfo = () => ({
      name: 'Yay-Tsa',
      device: 'Web Browser',
      deviceId: 'test-device',
      version: 'test',
    });
    setAuthErrorCallback = (cb: () => void) => {
      h.authErrorCallbacks.push(cb);
    };
    clearAuthErrorCallback = vi.fn();
  }
  class AuthService {
    constructor(_client: unknown) {}
    login = h.mockLogin;
    logout = h.mockLogout;
  }
  return {
    MediaServerClient,
    AuthService,
    AuthenticationError: h.AuthenticationError,
    getOrCreateDeviceId: () => 'test-device',
    DEFAULT_CLIENT_NAME: 'Yay-Tsa',
    DEFAULT_DEVICE_NAME: 'Web Browser',
    STORAGE_KEYS: {
      DEVICE_ID: 'yaytsa_device_id',
      SESSION: 'yaytsa_session',
      USER_ID: 'yaytsa_user_id',
      SERVER_URL: 'yaytsa_server_url',
      SESSION_PERSISTENT: 'yaytsa_session_persistent',
      USER_ID_PERSISTENT: 'yaytsa_user_id_persistent',
      SERVER_URL_PERSISTENT: 'yaytsa_server_url_persistent',
      REMEMBER_ME: 'yaytsa_remember_me',
    },
  };
});

vi.mock('@/shared/lib/query-client', () => ({ queryClient: { clear: vi.fn() } }));
vi.mock('@/features/player/stores/session-store', () => ({
  useSessionStore: { getState: () => ({ reset: vi.fn() }) },
}));

import { useAuthStore } from './auth.store';
import {
  saveSessionPersistent,
  loadSessionAuto,
  clearAllSessions,
} from '@/shared/utils/session-manager';

const STORED = { token: 'persisted-token', userId: 'user-7' };

function flush() {
  return new Promise(resolve => setTimeout(resolve, 0));
}

function resetStore() {
  useAuthStore.setState({
    client: null,
    authService: null,
    token: null,
    userId: null,
    isAdmin: false,
    isAuthenticated: false,
    isLoading: true,
    error: null,
  });
}

describe('auth.store', () => {
  beforeEach(() => {
    localStorage.clear();
    sessionStorage.clear();
    clearAllSessions();
    h.authErrorCallbacks.length = 0;
    h.mockGet.mockReset();
    h.mockLogin.mockReset();
    h.mockLogout.mockReset().mockResolvedValue(undefined);
    resetStore();
  });

  describe('restoreSession', () => {
    it('starts with isLoading=true so the UI shows a spinner, not a guest screen', () => {
      // Locks in that the very first render hydrates rather than flashing /login.
      expect(useAuthStore.getState().isLoading).toBe(true);
      expect(useAuthStore.getState().isAuthenticated).toBe(false);
    });

    it('returns false and stays guest when there is no persisted session', async () => {
      const ok = await useAuthStore.getState().restoreSession();
      expect(ok).toBe(false);
      expect(useAuthStore.getState().isAuthenticated).toBe(false);
      expect(useAuthStore.getState().isLoading).toBe(false);
    });

    it('restores an authenticated session when the backend accepts the token', async () => {
      saveSessionPersistent(STORED);
      h.mockGet.mockResolvedValue({ Policy: { IsAdministrator: true } });

      const ok = await useAuthStore.getState().restoreSession();

      expect(ok).toBe(true);
      const state = useAuthStore.getState();
      expect(state.isAuthenticated).toBe(true);
      expect(state.token).toBe(STORED.token);
      expect(state.userId).toBe(STORED.userId);
      expect(state.isAdmin).toBe(true);
      expect(state.isLoading).toBe(false);
      // Storage untouched — the session is intact.
      expect(loadSessionAuto()).toEqual(STORED);
    });

    it('clears the session and becomes guest ONLY on a confirmed 401', async () => {
      saveSessionPersistent(STORED);
      h.mockGet.mockRejectedValue(new h.AuthenticationError('unauthorized', 401));

      const ok = await useAuthStore.getState().restoreSession();

      expect(ok).toBe(false);
      expect(useAuthStore.getState().isAuthenticated).toBe(false);
      expect(loadSessionAuto()).toBeNull();
    });

    it('stays logged in on a network/backend error (backend restart resilience)', async () => {
      saveSessionPersistent(STORED);
      h.mockGet.mockRejectedValue(new Error('Network request failed: ECONNREFUSED'));

      const ok = await useAuthStore.getState().restoreSession();

      expect(ok).toBe(true);
      expect(useAuthStore.getState().isAuthenticated).toBe(true);
      expect(useAuthStore.getState().token).toBe(STORED.token);
      // Critical: the persisted session must NOT be wiped by a transient outage.
      expect(loadSessionAuto()).toEqual(STORED);
    });

    it('stays logged in on a 403 (permission error is not an auth failure)', async () => {
      saveSessionPersistent(STORED);
      h.mockGet.mockRejectedValue(new h.AuthenticationError('forbidden', 403));

      const ok = await useAuthStore.getState().restoreSession();

      expect(ok).toBe(true);
      expect(useAuthStore.getState().isAuthenticated).toBe(true);
      expect(loadSessionAuto()).toEqual(STORED);
    });
  });

  describe('login persistence default', () => {
    const response = {
      AccessToken: 'fresh-token',
      User: { Id: 'user-9', Policy: { IsAdministrator: false } },
    };

    it('persists to localStorage by default (survives reload/PWA restart)', async () => {
      h.mockLogin.mockResolvedValue(response);

      await useAuthStore.getState().login('alice', 'pw');

      expect(useAuthStore.getState().isAuthenticated).toBe(true);
      expect(localStorage.getItem('yaytsa_session_persistent')).toBe('fresh-token');
      expect(localStorage.getItem('yaytsa_remember_me')).toBe('true');
    });

    it('uses tab-scoped sessionStorage when Remember me is explicitly disabled', async () => {
      h.mockLogin.mockResolvedValue(response);

      await useAuthStore.getState().login('alice', 'pw', { rememberMe: false });

      expect(sessionStorage.getItem('yaytsa_session')).toBe('fresh-token');
      expect(localStorage.getItem('yaytsa_session_persistent')).toBeNull();
    });
  });

  describe('logout', () => {
    it('clears storage, becomes guest, and allows re-login without reload', async () => {
      saveSessionPersistent(STORED);
      h.mockGet.mockResolvedValue({ Policy: { IsAdministrator: false } });
      await useAuthStore.getState().restoreSession();
      expect(useAuthStore.getState().isAuthenticated).toBe(true);

      await useAuthStore.getState().logout();

      expect(useAuthStore.getState().isAuthenticated).toBe(false);
      expect(useAuthStore.getState().isLoading).toBe(false);
      expect(loadSessionAuto()).toBeNull();

      // Re-login in the same browsing session must work.
      h.mockLogin.mockResolvedValue({
        AccessToken: 'token-2',
        User: { Id: 'user-2', Policy: { IsAdministrator: false } },
      });
      await useAuthStore.getState().login('bob', 'pw');
      expect(useAuthStore.getState().isAuthenticated).toBe(true);
      expect(useAuthStore.getState().token).toBe('token-2');
    });

    it('does not cascade: parallel 401s trigger a single logout', async () => {
      saveSessionPersistent(STORED);
      h.mockGet.mockResolvedValue({ Policy: { IsAdministrator: false } });
      await useAuthStore.getState().restoreSession();

      const callback = h.authErrorCallbacks[h.authErrorCallbacks.length - 1]!;
      callback();
      callback();
      callback();
      await flush();

      expect(h.mockLogout).toHaveBeenCalledTimes(1);
      expect(useAuthStore.getState().isAuthenticated).toBe(false);
    });
  });
});
