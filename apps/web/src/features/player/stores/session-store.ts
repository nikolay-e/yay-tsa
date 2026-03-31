import { create } from 'zustand';
import { useShallow } from 'zustand/react/shallow';
import {
  AdaptiveDjService,
  ItemsService,
  type ListeningSession,
  type SessionState,
  type AdaptiveQueueTrack,
  type AudioItem,
  type PlaybackSignal,
} from '@yay-tsa/core';
import { useAuthStore } from '@/features/auth/stores/auth.store';
import { log } from '@/shared/utils/logger';
import { toast } from '@/shared/ui/Toast';
import { usePlayerStore } from './player.store';

const DJ_SESSION_KEY = 'yaytsa_dj_session';
const MAX_REFRESH_ERRORS = 3;

interface SessionStoreState {
  activeSession: ListeningSession | null;
  isRefreshing: boolean;
  isStarting: boolean;
  error: Error | null;
}

interface SessionStoreActions {
  startSession: (seedTrackId?: string) => Promise<void>;
  endSession: () => Promise<void>;
  reset: () => void;
  restoreSession: () => Promise<void>;
  refreshQueue: () => Promise<void>;
  sendSignal: (signal: PlaybackSignal) => Promise<void>;
}

type SessionStore = SessionStoreState & SessionStoreActions;

const initialState: SessionStoreState = {
  activeSession: null,
  isRefreshing: false,
  isStarting: false,
  error: null,
};

function getDjService(): AdaptiveDjService | null {
  const client = useAuthStore.getState().client;
  if (!client) return null;
  return new AdaptiveDjService(client);
}

function getItemsService(): ItemsService | null {
  const client = useAuthStore.getState().client;
  if (!client) return null;
  return new ItemsService(client);
}

function buildSessionState(): SessionState {
  return {
    energy: 5,
    intensity: 5,
    moodTags: [],
    attentionMode: 'active',
    constraints: [],
  };
}

function saveSession(sessionId: string | null) {
  if (sessionId) {
    localStorage.setItem(DJ_SESSION_KEY, sessionId);
  } else {
    localStorage.removeItem(DJ_SESSION_KEY);
  }
}

export function getSavedSessionId(): string | null {
  return localStorage.getItem(DJ_SESSION_KEY);
}

async function resolveAudioItems(tracks: AdaptiveQueueTrack[]): Promise<AudioItem[]> {
  const itemsService = getItemsService();
  if (!itemsService || tracks.length === 0) return [];

  try {
    return await itemsService.getItemsByIds(tracks.map(t => t.trackId));
  } catch (error) {
    log.player.error('Failed to batch-resolve audio items', error);
    return [];
  }
}

let refreshDebounce = false;
let restoreInProgress = false;
let consecutiveRefreshErrors = 0;
const lastSignalTimestamps = new Map<string, number>();

export const useSessionStore = create<SessionStore>()((set, get) => ({
  ...initialState,

  startSession: async (seedTrackId?: string) => {
    if (get().isStarting) return;

    const service = getDjService();
    if (!service) return;

    const { activeSession } = get();
    if (activeSession) {
      await service.endSession(activeSession.id).catch(() => {});
      saveSession(null);
      set({ activeSession: null });
    }

    const sessionState = buildSessionState();

    set({ isStarting: true, error: null });
    try {
      const session = await service.startSession(sessionState, seedTrackId);
      set({ activeSession: session });
      saveSession(session.id);

      await service.refreshQueue(session.id);
      const djQueue = await service.getQueue(session.id);
      const audioItems = await resolveAudioItems(djQueue);
      if (audioItems.length > 0) {
        if (seedTrackId) {
          await usePlayerStore.getState().playTracks(audioItems);
        } else {
          usePlayerStore.getState().appendToQueue(audioItems);
        }
      }
      set({ isStarting: false });
    } catch (error) {
      log.player.error('Failed to start DJ session', error);
      set({
        isStarting: false,
        error: error instanceof Error ? error : new Error(String(error)),
      });
      toast.add('error', 'Failed to start DJ');
    }
  },

  restoreSession: async () => {
    const service = getDjService();
    if (!service || restoreInProgress) return;

    restoreInProgress = true;
    const safetyTimer = setTimeout(() => {
      restoreInProgress = false;
    }, 10_000);
    set({ isStarting: true, error: null });
    try {
      const session = await service.getActiveSession();
      if (!session) {
        saveSession(null);
        set({ isStarting: false });
        return;
      }

      set({ activeSession: session });
      saveSession(session.id);

      const djQueue = await service.getQueue(session.id);
      const audioItems = await resolveAudioItems(djQueue);
      if (audioItems.length > 0) {
        usePlayerStore.getState().appendToQueue(audioItems);
      }
      set({ isStarting: false });
    } catch (error) {
      log.player.error('Failed to restore DJ session', error);
      saveSession(null);
      set({
        isStarting: false,
        error: error instanceof Error ? error : new Error(String(error)),
      });
    } finally {
      clearTimeout(safetyTimer);
      restoreInProgress = false;
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
    consecutiveRefreshErrors = 0;
    saveSession(null);
    set({ activeSession: null, isRefreshing: false, isStarting: false, error: null });
    toast.add('info', 'DJ off, queue kept');
  },

  reset: () => {
    consecutiveRefreshErrors = 0;
    saveSession(null);
    set({ activeSession: null, isRefreshing: false, isStarting: false, error: null });
  },

  refreshQueue: async () => {
    const { activeSession, isRefreshing } = get();
    const service = getDjService();
    if (!service || !activeSession || isRefreshing || refreshDebounce) return;

    refreshDebounce = true;
    set({ isRefreshing: true });
    try {
      await service.refreshQueue(activeSession.id);
      const djQueue = await service.getQueue(activeSession.id);

      consecutiveRefreshErrors = 0;

      const existingIds = new Set(usePlayerStore.getState().queueItems.map(i => i.Id));
      const newTracks = djQueue.filter(t => !existingIds.has(t.trackId));
      if (newTracks.length > 0) {
        const playNextTracks = newTracks.filter(t => t.intentLabel === 'play_next');
        const appendTracks = newTracks.filter(t => t.intentLabel !== 'play_next');

        const [playNextItems, appendItems] = await Promise.all([
          playNextTracks.length > 0 ? resolveAudioItems(playNextTracks) : Promise.resolve([]),
          appendTracks.length > 0 ? resolveAudioItems(appendTracks) : Promise.resolve([]),
        ]);

        if (playNextItems.length > 0) {
          usePlayerStore.getState().insertNextInQueue(playNextItems);
        }
        if (appendItems.length > 0) {
          usePlayerStore.getState().appendToQueue(appendItems);
        }
      }
      set({ isRefreshing: false, error: null });
    } catch (error) {
      consecutiveRefreshErrors++;
      log.player.error(
        `Failed to refresh queue (attempt ${consecutiveRefreshErrors}/${MAX_REFRESH_ERRORS})`,
        error
      );

      if (consecutiveRefreshErrors >= MAX_REFRESH_ERRORS) {
        log.player.error('DJ session appears dead, clearing session');
        saveSession(null);
        set({
          isRefreshing: false,
          activeSession: null,
          error: new Error('DJ session expired'),
        });
        return;
      }

      set({
        isRefreshing: false,
        error: error instanceof Error ? error : new Error(String(error)),
      });
    } finally {
      refreshDebounce = false;
    }
  },

  sendSignal: async (signal: PlaybackSignal) => {
    const { activeSession } = get();
    const service = getDjService();
    if (!service || !activeSession) return;

    const throttleKey = `${activeSession.id}:${signal.signalType}`;
    const lastSent = lastSignalTimestamps.get(throttleKey) ?? 0;
    if (Date.now() - lastSent < 500) return;
    lastSignalTimestamps.set(throttleKey, Date.now());

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
export const useIsSessionRefreshing = () => useSessionStore(state => state.isRefreshing);
export const useIsSessionStarting = () => useSessionStore(state => state.isStarting);
export const useSessionError = () => useSessionStore(state => state.error);
export const useSessionActions = () =>
  useSessionStore(
    useShallow(state => ({
      startSession: state.startSession,
      endSession: state.endSession,
      reset: state.reset,
      restoreSession: state.restoreSession,
      refreshQueue: state.refreshQueue,
      sendSignal: state.sendSignal,
    }))
  );
