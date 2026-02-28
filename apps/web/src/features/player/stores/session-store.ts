import { create } from 'zustand';
import { useShallow } from 'zustand/react/shallow';
import {
  AdaptiveDjService,
  type ListeningSession,
  type SessionState,
  type AdaptiveQueueTrack,
  type PlaybackSignal,
} from '@yay-tsa/core';
import { useAuthStore } from '@/features/auth/stores/auth.store';
import { log } from '@/shared/utils/logger';

interface SessionStoreState {
  activeSession: ListeningSession | null;
  queue: AdaptiveQueueTrack[];
  isRefreshing: boolean;
  isStarting: boolean;
  error: Error | null;
}

interface SessionStoreActions {
  startSession: (state: SessionState) => Promise<void>;
  updateMood: (state: SessionState) => Promise<void>;
  endSession: () => Promise<void>;
  refreshQueue: () => Promise<void>;
  sendSignal: (signal: PlaybackSignal) => Promise<void>;
}

type SessionStore = SessionStoreState & SessionStoreActions;

const initialState: SessionStoreState = {
  activeSession: null,
  queue: [],
  isRefreshing: false,
  isStarting: false,
  error: null,
};

function getDjService(): AdaptiveDjService | null {
  const client = useAuthStore.getState().client;
  if (!client) return null;
  return new AdaptiveDjService(client);
}

export const useSessionStore = create<SessionStore>()((set, get) => ({
  ...initialState,

  startSession: async (sessionState: SessionState) => {
    const service = getDjService();
    if (!service) return;

    set({ isStarting: true, error: null });
    try {
      const session = await service.startSession(sessionState);
      const queue = await service.getQueue(session.id);
      set({ activeSession: session, queue, isStarting: false });
    } catch (error) {
      log.player.error('Failed to start DJ session', error);
      set({
        isStarting: false,
        error: error instanceof Error ? error : new Error(String(error)),
      });
    }
  },

  updateMood: async (sessionState: SessionState) => {
    const { activeSession } = get();
    const service = getDjService();
    if (!service || !activeSession) return;

    try {
      await service.updateSessionState(activeSession.id, sessionState);
      set({
        activeSession: { ...activeSession, state: sessionState },
      });
      const queue = await service.getQueue(activeSession.id);
      set({ queue });
    } catch (error) {
      log.player.error('Failed to update mood', error);
      set({ error: error instanceof Error ? error : new Error(String(error)) });
    }
  },

  endSession: async () => {
    const { activeSession } = get();
    const service = getDjService();
    if (!service || !activeSession) return;

    try {
      await service.endSession(activeSession.id);
    } catch (error) {
      log.player.warn('Failed to end DJ session gracefully', {
        error: String(error),
      });
    }
    set(initialState);
  },

  refreshQueue: async () => {
    const { activeSession } = get();
    const service = getDjService();
    if (!service || !activeSession) return;

    set({ isRefreshing: true });
    try {
      await service.refreshQueue(activeSession.id);
      const queue = await service.getQueue(activeSession.id);
      set({ queue, isRefreshing: false });
    } catch (error) {
      log.player.error('Failed to refresh queue', error);
      set({
        isRefreshing: false,
        error: error instanceof Error ? error : new Error(String(error)),
      });
    }
  },

  sendSignal: async (signal: PlaybackSignal) => {
    const { activeSession } = get();
    const service = getDjService();
    if (!service || !activeSession) return;

    try {
      await service.sendSignal(activeSession.id, signal);
    } catch (error) {
      log.player.warn('Failed to send playback signal', {
        error: String(error),
      });
    }
  },
}));

export const useActiveSession = () => useSessionStore(state => state.activeSession);
export const useSessionQueue = () => useSessionStore(state => state.queue);
export const useIsSessionRefreshing = () => useSessionStore(state => state.isRefreshing);
export const useIsSessionStarting = () => useSessionStore(state => state.isStarting);
export const useSessionError = () => useSessionStore(state => state.error);
export const useSessionActions = () =>
  useSessionStore(
    useShallow(state => ({
      startSession: state.startSession,
      updateMood: state.updateMood,
      endSession: state.endSession,
      refreshQueue: state.refreshQueue,
      sendSignal: state.sendSignal,
    }))
  );
