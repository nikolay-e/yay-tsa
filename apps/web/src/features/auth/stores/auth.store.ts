import { create } from 'zustand';
import { subscribeWithSelector } from 'zustand/middleware';
import {
  MediaServerClient,
  AuthService,
  getOrCreateDeviceId,
  APP_VERSION,
  DEFAULT_CLIENT_NAME,
  DEFAULT_DEVICE_NAME,
  type ClientInfo,
} from '@yaytsa/core';
import {
  saveSession,
  saveSessionPersistent,
  loadSessionAuto,
  clearAllSessions,
} from '@/shared/utils/session-manager';

const VOLUME_STORAGE_KEY = 'yaytsa_volume';
const API_BASE_PATH = '/api';

interface LoginOptions {
  rememberMe?: boolean;
}

interface AuthState {
  client: MediaServerClient | null;
  authService: AuthService | null;
  token: string | null;
  userId: string | null;
  isAuthenticated: boolean;
  isLoading: boolean;
  error: Error | null;
}

interface AuthActions {
  login: (username: string, password: string, options?: LoginOptions) => Promise<void>;
  logout: () => Promise<void>;
  restoreSession: () => Promise<boolean>;
}

type AuthStore = AuthState & AuthActions;

const initialState: AuthState = {
  client: null,
  authService: null,
  token: null,
  userId: null,
  isAuthenticated: false,
  isLoading: false,
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

async function clearCaches(): Promise<void> {
  if ('caches' in window) {
    try {
      const cacheNames = await caches.keys();
      await Promise.all(
        cacheNames
          .filter(name => name.startsWith('yaytsa-'))
          .map(async name => caches.delete(name))
      );
    } catch {
      // Ignore cache errors
    }
  }
}

export const useAuthStore = create<AuthStore>()(
  subscribeWithSelector((set, get) => ({
    ...initialState,

    login: async (username, password, options) => {
      set({ isLoading: true, error: null });

      try {
        const clientInfo = createClientInfo();
        const client = new MediaServerClient(API_BASE_PATH, clientInfo);
        const authService = new AuthService(client);

        client.setAuthErrorCallback(() => {
          void get().logout();
        });

        const response = await authService.login(username, password);

        const sessionData = {
          token: response.AccessToken,
          userId: response.User.Id,
        };

        if (options?.rememberMe) {
          saveSessionPersistent(sessionData);
        } else {
          saveSession(sessionData);
        }

        set({
          client,
          authService,
          token: response.AccessToken,
          userId: response.User.Id,
          isAuthenticated: true,
          isLoading: false,
          error: null,
        });
      } catch (error) {
        set({
          isLoading: false,
          error: error instanceof Error ? error : new Error(String(error)),
        });
        throw error;
      }
    },

    logout: async () => {
      const { authService } = get();

      if (authService) {
        try {
          await authService.logout();
        } catch {
          // Ignore logout errors
        }
      }

      clearAllSessions();
      localStorage.removeItem(VOLUME_STORAGE_KEY);

      await clearCaches();

      set(initialState);
    },

    restoreSession: async () => {
      const session = loadSessionAuto();
      if (!session) return false;

      set({ isLoading: true });

      try {
        const clientInfo = createClientInfo();
        const client = new MediaServerClient(API_BASE_PATH, clientInfo);
        client.setToken(session.token, session.userId);

        client.setAuthErrorCallback(() => {
          void get().logout();
        });

        await client.getServerInfo();

        const authService = new AuthService(client);

        set({
          client,
          authService,
          token: session.token,
          userId: session.userId,
          isAuthenticated: true,
          isLoading: false,
          error: null,
        });

        return true;
      } catch {
        clearAllSessions();
        set({
          ...initialState,
          isLoading: false,
          error: new Error('Session expired or invalid'),
        });
        return false;
      }
    },
  }))
);

export const useIsAuthenticated = () =>
  useAuthStore(state => state.isAuthenticated);
export const useClient = () => useAuthStore(state => state.client);
export const useIsLoading = () => useAuthStore(state => state.isLoading);
export const useAuthError = () => useAuthStore(state => state.error);
