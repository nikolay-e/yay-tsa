import { create } from 'zustand';
import { subscribeWithSelector } from 'zustand/middleware';
import {
  MediaServerClient,
  AuthService,
  AuthenticationError,
  getOrCreateDeviceId,
  DEFAULT_CLIENT_NAME,
  DEFAULT_DEVICE_NAME,
  type ClientInfo,
} from '@yay-tsa/core';
import {
  saveSession,
  saveSessionPersistent,
  loadSessionAuto,
  clearAllSessions,
  promoteLegacySession,
} from '@/shared/utils/session-manager';
import { queryClient } from '@/shared/lib/query-client';
import { log } from '@/shared/utils/logger';
import { toError } from '@/shared/utils/to-error';

const APP_VERSION = (import.meta.env.VITE_APP_VERSION as string | undefined) ?? 'dev';

const runtimeServerUrl = globalThis.window?.__YAYTSA_CONFIG__?.serverUrl?.trim();
const API_BASE_PATH = runtimeServerUrl?.length ? runtimeServerUrl : '/api';

export const SESSION_EXPIRED_FLAG = 'yaytsa_session_expired';

// User-scoped localStorage that must not leak to the next account on a shared
// device. Deliberately excludes yaytsa_volume (a device preference) and
// yaytsa_device_id (device identity).
const USER_SCOPED_KEY_PREFIXES = ['yaytsa_resume:', 'yaytsa_book_speed_'];
const USER_SCOPED_KEYS = new Set(['yaytsa_audiobook_speed', 'yaytsa_sort_prefs']);

interface LoginOptions {
  rememberMe?: boolean;
}

interface LogoutOptions {
  auto?: boolean;
}

interface AuthState {
  client: MediaServerClient | null;
  authService: AuthService | null;
  token: string | null;
  userId: string | null;
  isAdmin: boolean;
  isAuthenticated: boolean;
  isLoading: boolean;
  error: Error | null;
}

interface AuthActions {
  login: (username: string, password: string, options?: LoginOptions) => Promise<void>;
  logout: (options?: LogoutOptions) => Promise<void>;
  changePassword: (currentPassword: string, newPassword: string) => Promise<void>;
  restoreSession: () => Promise<boolean>;
}

type AuthStore = AuthState & AuthActions;

const initialState: AuthState = {
  client: null,
  authService: null,
  token: null,
  userId: null,
  isAdmin: false,
  isAuthenticated: false,
  isLoading: true,
  error: null,
};

function createClientInfo(): ClientInfo {
  return {
    name: DEFAULT_CLIENT_NAME,
    device: DEFAULT_DEVICE_NAME,
    deviceId: getOrCreateDeviceId(),
    version: APP_VERSION,
  };
}

function clearUserScopedStorage(): void {
  try {
    const keysToRemove: string[] = [];
    for (let i = 0; i < localStorage.length; i++) {
      const key = localStorage.key(i);
      if (!key) continue;
      if (
        USER_SCOPED_KEY_PREFIXES.some(prefix => key.startsWith(prefix)) ||
        USER_SCOPED_KEYS.has(key)
      ) {
        keysToRemove.push(key);
      }
    }
    for (const key of keysToRemove) localStorage.removeItem(key);
  } catch {
    // Ignore storage errors
  }
}

async function clearCaches(): Promise<void> {
  if ('caches' in globalThis) {
    try {
      const cacheNames = await caches.keys();
      await Promise.all(
        cacheNames.filter(name => name.startsWith('yaytsa-')).map(async name => caches.delete(name))
      );
    } catch {
      // Ignore cache errors
    }
  }
}

// Guards against a logout cascade: when several in-flight requests receive a
// 401 at the same time, each one fires the auth-error callback. Only the first
// should actually tear down the session.
let logoutInFlight = false;

export const useAuthStore = create<AuthStore>()(
  subscribeWithSelector((set, get) => {
    // Auto-logout on a confirmed authentication failure (HTTP 401). The core
    // client only invokes this for 401 (never 403), i.e. the token is no longer
    // accepted by the backend.
    const handleAuthError = () => {
      if (!get().isAuthenticated && !logoutInFlight) return;
      get()
        .logout({ auto: true })
        .catch(() => {});
    };

    return {
      ...initialState,

      login: async (username, password, options) => {
        set({ isLoading: true, error: null });

        try {
          const clientInfo = createClientInfo();
          const client = new MediaServerClient(API_BASE_PATH, clientInfo);
          const authService = new AuthService(client);

          const response = await authService.login(username, password);

          client.setAuthErrorCallback(handleAuthError);

          const sessionData = {
            token: response.AccessToken,
            userId: response.User.Id,
          };

          // Default to persistent (localStorage) storage so the login survives a
          // reload, a frontend version bump, and closing/reopening an installed
          // PWA. Only an explicit opt-out ("Remember me" unchecked) downgrades to
          // tab-scoped sessionStorage.
          if (options?.rememberMe === false) {
            saveSession(sessionData);
          } else {
            saveSessionPersistent(sessionData);
          }

          set({
            client,
            authService,
            token: response.AccessToken,
            userId: response.User.Id,
            isAdmin: response.User.Policy?.IsAdministrator ?? false,
            isAuthenticated: true,
            isLoading: false,
            error: null,
          });
        } catch (error) {
          log.auth.error('Login failed', error, { username });
          set({
            isLoading: false,
            error: toError(error),
          });
          throw error;
        }
      },

      logout: async options => {
        if (logoutInFlight) return;
        logoutInFlight = true;

        try {
          const { authService } = get();

          if (authService) {
            try {
              await authService.logout();
            } catch (error) {
              log.auth.warn('Logout request failed, continuing with local cleanup', {
                error: String(error),
              });
            }
          }

          clearAllSessions();
          clearUserScopedStorage();

          if (options?.auto) {
            // A confirmed 401 may be transient (revoked device, server-side
            // expiry). Keep offline downloads — wiping gigabytes on an auth
            // hiccup is unacceptable — and tell the login page why we're here.
            try {
              sessionStorage.setItem(SESSION_EXPIRED_FLAG, '1');
            } catch {
              // Ignore storage errors
            }
          } else {
            // Explicit sign-out on a possibly shared device: downloads are
            // user-scoped data and must not leak to the next account.
            import('@/features/offline/stores/offline.store')
              .then(async m => m.useOfflineStore.getState().clearAll())
              .catch(() => {});
          }

          await clearCaches();
          queryClient.clear();
          import('@/features/player/stores/session-store')
            .then(m => m.useSessionStore.getState().reset())
            .catch(() => {});

          set({ ...initialState, isLoading: false });
        } finally {
          logoutInFlight = false;
        }
      },

      changePassword: async (currentPassword, newPassword) => {
        const { authService, userId } = get();
        if (!authService || !userId) {
          throw new AuthenticationError('Not authenticated');
        }

        // The backend revokes every token (including the caller's) and reissues
        // a fresh one for this device. Swap the stored token in place so the
        // session continues without a forced re-login.
        const newToken = await authService.changePassword(currentPassword, newPassword);

        const existing = loadSessionAuto();
        const sessionData = { token: newToken, userId };
        if (existing?.persistent) {
          saveSessionPersistent(sessionData);
        } else {
          saveSession(sessionData);
        }

        set({ token: newToken });
      },

      restoreSession: async () => {
        const session = loadSessionAuto();
        if (!session) {
          set({ ...initialState, isLoading: false });
          return false;
        }

        const clientInfo = createClientInfo();
        const client = new MediaServerClient(API_BASE_PATH, clientInfo);
        client.setToken(session.token, session.userId);
        const authService = new AuthService(client);
        client.setAuthErrorCallback(handleAuthError);

        try {
          // Confirm the persisted token is still accepted by the backend.
          const me = await client.get<{ Policy?: { IsAdministrator?: boolean } }>('/Users/Me');

          // Migrate users who logged in with an older app version (session only
          // in sessionStorage) to persistent storage so they survive a restart.
          promoteLegacySession(session);

          set({
            client,
            authService,
            token: session.token,
            userId: session.userId,
            isAdmin: me?.Policy?.IsAdministrator ?? false,
            isAuthenticated: true,
            isLoading: false,
            error: null,
          });

          return true;
        } catch (error) {
          // Only a confirmed 401 means the session is genuinely invalid. Any
          // other failure (backend down/restarting, network blip, 5xx) must NOT
          // log the user out — we keep the persisted credentials and stay
          // authenticated. A later real 401 on any request tears the session
          // down via handleAuthError.
          if (error instanceof AuthenticationError && error.statusCode === 401) {
            clearAllSessions();
            try {
              sessionStorage.setItem(SESSION_EXPIRED_FLAG, '1');
            } catch {
              // Ignore storage errors
            }
            set({ ...initialState, isLoading: false });
            return false;
          }

          log.auth.warn('Session validation deferred — backend unreachable, keeping session', {
            error: String(error),
          });

          // Even when we cannot reach the backend, promote a legacy tab-scoped
          // session so a subsequent PWA restart does not silently lose it. A
          // genuinely invalid token is still cleared by the 401 path later.
          promoteLegacySession(session);

          set({
            client,
            authService,
            token: session.token,
            userId: session.userId,
            isAdmin: false,
            isAuthenticated: true,
            isLoading: false,
            error: null,
          });

          return true;
        }
      },
    };
  })
);

export const useClient = () => useAuthStore(state => state.client);
export const useIsAdmin = () => useAuthStore(state => state.isAdmin);
