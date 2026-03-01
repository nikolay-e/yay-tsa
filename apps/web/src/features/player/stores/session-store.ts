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

export type DjMode = 'conservative' | 'explorer' | 'adventurer';

interface SessionStoreState {
  activeSession: ListeningSession | null;
  djMode: DjMode;
  energyLevel: number;
  isRefreshing: boolean;
  isStarting: boolean;
  error: Error | null;
}

interface SessionStoreActions {
  startSession: () => Promise<void>;
  updateMood: () => Promise<void>;
  endSession: () => Promise<void>;
  refreshQueue: () => Promise<void>;
  sendSignal: (signal: PlaybackSignal) => Promise<void>;
  setDjMode: (mode: DjMode) => void;
  setEnergyLevel: (level: number) => void;
}

type SessionStore = SessionStoreState & SessionStoreActions;

const initialState: SessionStoreState = {
  activeSession: null,
  djMode: 'conservative',
  energyLevel: 3,
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

function buildSessionState(mode: DjMode, energyLevel: number): SessionState {
  const energy = energyLevel * 2;
  const intensity =
    mode === 'adventurer'
      ? 6 + (energyLevel - 1) * 0.5
      : mode === 'explorer'
        ? 5 + (energyLevel - 1) * 0.25
        : energy;
  const moodTags = mode === 'adventurer' ? ['discovery'] : mode === 'explorer' ? ['explore'] : [];
  return {
    energy,
    intensity,
    moodTags,
    attentionMode: 'active',
    constraints: [],
  };
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

export const useSessionStore = create<SessionStore>()((set, get) => ({
  ...initialState,

  setDjMode: (mode: DjMode) => set({ djMode: mode }),
  setEnergyLevel: (level: number) => set({ energyLevel: Math.max(1, Math.min(5, level)) }),

  startSession: async () => {
    const service = getDjService();
    if (!service) return;

    const { djMode, energyLevel } = get();
    const sessionState = buildSessionState(djMode, energyLevel);

    set({ isStarting: true, error: null });
    try {
      const session = await service.startSession(sessionState);
      set({ activeSession: session, isStarting: false });

      const djQueue = await service.getQueue(session.id);
      const audioItems = await resolveAudioItems(djQueue);
      if (audioItems.length > 0) {
        await usePlayerStore.getState().playTracks(audioItems);
      }
    } catch (error) {
      log.player.error('Failed to start DJ session', error);
      set({
        isStarting: false,
        error: error instanceof Error ? error : new Error(String(error)),
      });
      toast.add('error', 'Failed to start DJ');
    }
  },

  updateMood: async () => {
    const { activeSession, djMode, energyLevel } = get();
    const service = getDjService();
    if (!service || !activeSession) return;

    const sessionState = buildSessionState(djMode, energyLevel);

    try {
      await service.updateSessionState(activeSession.id, sessionState);
      set({ activeSession: { ...activeSession, state: sessionState } });
      await get().refreshQueue();
    } catch (error) {
      log.player.error('Failed to update mood', error);
      set({ error: error instanceof Error ? error : new Error(String(error)) });
      toast.add('error', 'Failed to update DJ mood');
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
    set({ activeSession: null, isRefreshing: false, isStarting: false, error: null });
    toast.add('info', 'DJ off, queue kept');
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

      const existingIds = new Set(usePlayerStore.getState().queueItems.map(i => i.Id));
      const newTracks = djQueue.filter(t => !existingIds.has(t.trackId));
      if (newTracks.length > 0) {
        const audioItems = await resolveAudioItems(newTracks);
        if (audioItems.length > 0) {
          usePlayerStore.getState().appendToQueue(audioItems);
        }
      }
      set({ isRefreshing: false });
    } catch (error) {
      log.player.error('Failed to refresh queue', error);
      set({
        isRefreshing: false,
        error: error instanceof Error ? error : new Error(String(error)),
      });
      toast.add('error', 'Failed to refresh DJ queue');
    } finally {
      refreshDebounce = false;
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
export const useDjMode = () => useSessionStore(state => state.djMode);
export const useEnergyLevel = () => useSessionStore(state => state.energyLevel);
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
      setDjMode: state.setDjMode,
      setEnergyLevel: state.setEnergyLevel,
    }))
  );
