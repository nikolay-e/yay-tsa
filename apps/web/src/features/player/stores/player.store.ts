import { create } from 'zustand';
import { subscribeWithSelector } from 'zustand/middleware';
import {
  PlaybackQueue,
  PlaybackReporter,
  ItemsService,
  type AudioItem,
  type MediaServerClient,
} from '@yaytsa/core';
import { HTML5AudioEngine, MediaSessionManager, type AudioEngine } from '@yaytsa/platform';
import { useAuthStore } from '@/features/auth/stores/auth.store';
import { useTimingStore } from './timing.store';

export type RepeatMode = 'off' | 'one' | 'all';

interface PlayerState {
  queue: PlaybackQueue;
  currentTrack: AudioItem | null;
  isPlaying: boolean;
  isLoading: boolean;
  isShuffle: boolean;
  repeatMode: RepeatMode;
  volume: number;
  error: Error | null;
}

interface PlayerActions {
  playTrack: (track: AudioItem) => Promise<void>;
  playTracks: (tracks: AudioItem[], startIndex?: number) => Promise<void>;
  playAlbum: (albumId: string, startIndex?: number) => Promise<void>;
  pause: () => void;
  resume: () => Promise<void>;
  next: () => Promise<void>;
  previous: () => Promise<void>;
  seek: (seconds: number) => void;
  setVolume: (level: number) => void;
  toggleShuffle: () => void;
  toggleRepeat: () => void;
  stop: () => void;
}

type PlayerStore = PlayerState & PlayerActions;

const VOLUME_STORAGE_KEY = 'yaytsa_volume';

let audioEngine: AudioEngine | null = null;
let mediaSession: MediaSessionManager | null = null;
let playbackReporter: PlaybackReporter | null = null;
let currentItemId: string | null = null;
let currentClient: MediaServerClient | null = null;
let currentLoadId: symbol | null = null;

function getAudioEngine(): AudioEngine {
  audioEngine ??= new HTML5AudioEngine();
  return audioEngine;
}

function getMediaSession(): MediaSessionManager {
  mediaSession ??= new MediaSessionManager();
  return mediaSession;
}

function getSavedVolume(): number {
  try {
    const saved = localStorage.getItem(VOLUME_STORAGE_KEY);
    if (saved) {
      const parsed = parseFloat(saved);
      if (!isNaN(parsed) && parsed >= 0 && parsed <= 1) {
        return parsed;
      }
    }
  } catch {
    // Ignore storage errors
  }
  return 1;
}

function saveVolume(volume: number): void {
  try {
    localStorage.setItem(VOLUME_STORAGE_KEY, volume.toString());
  } catch {
    // Ignore storage errors
  }
}

const initialState: PlayerState = {
  queue: new PlaybackQueue(),
  currentTrack: null,
  isPlaying: false,
  isLoading: false,
  isShuffle: false,
  repeatMode: 'off',
  volume: getSavedVolume(),
  error: null,
};

export const usePlayerStore = create<PlayerStore>()(
  subscribeWithSelector((set, get) => {
    const engine = getAudioEngine();
    const session = getMediaSession();

    engine.setVolume(initialState.volume);

    engine.onTimeUpdate((seconds) => {
      const duration = engine.getDuration();
      useTimingStore.getState().updateTiming(seconds, duration);

      if (playbackReporter && currentItemId) {
        playbackReporter.reportProgress(currentItemId, seconds, false).catch(() => {});
      }
    });

    engine.onEnded(() => {
      const { repeatMode, queue, next } = get();

      if (repeatMode === 'one') {
        engine.seek(0);
        void engine.play();
      } else if (queue.hasNext() || repeatMode === 'all') {
        void next();
      } else {
        set({ isPlaying: false });
      }
    });

    engine.onLoading((isLoading) => {
      set({ isLoading });
    });

    engine.onError((error) => {
      set({ error, isPlaying: false, isLoading: false });
    });

    session.setActionHandlers({
      onPlay: () => void get().resume(),
      onPause: () => get().pause(),
      onSeek: (seconds) => get().seek(seconds),
      onNext: () => void get().next(),
      onPrevious: () => void get().previous(),
    });

    async function loadAndPlay(track: AudioItem): Promise<void> {
      const loadId = Symbol('load');
      currentLoadId = loadId;

      if (!currentClient) {
        throw new Error('Not authenticated');
      }

      set({ isLoading: true, error: null });

      try {
        const itemsService = new ItemsService(currentClient);
        const streamUrl = itemsService.getStreamUrl(track.Id);

        await engine.load(streamUrl);

        if (currentLoadId !== loadId) {
          return;
        }

        const imageUrl = track.AlbumPrimaryImageTag
          ? currentClient.getImageUrl(track.AlbumId ?? track.Id, 'Primary', {
              tag: track.AlbumPrimaryImageTag,
              maxWidth: 256,
              maxHeight: 256,
            })
          : undefined;

        session.updateMetadata({
          title: track.Name,
          artist: track.Artists?.join(', ') ?? 'Unknown Artist',
          album: track.Album ?? 'Unknown Album',
          artwork: imageUrl,
        });

        if (playbackReporter && currentItemId) {
          await playbackReporter.reportStopped(currentItemId, engine.getCurrentTime());
        }

        currentItemId = track.Id;
        playbackReporter = new PlaybackReporter(currentClient);
        await playbackReporter.reportStart(track.Id);

        await engine.play();

        if (currentLoadId !== loadId) {
          return;
        }

        set({
          currentTrack: track,
          isPlaying: true,
          isLoading: false,
          error: null,
        });
      } catch (error) {
        if (currentLoadId !== loadId) {
          return;
        }
        set({
          error: error instanceof Error ? error : new Error(String(error)),
          isPlaying: false,
          isLoading: false,
        });
        throw error;
      }
    }

    return {
      ...initialState,

      playTrack: async (track) => {
        const { queue } = get();
        queue.setQueue([track], 0);
        await loadAndPlay(track);
      },

      playTracks: async (tracks, startIndex = 0) => {
        if (tracks.length === 0) return;

        const { queue, isShuffle } = get();
        queue.setQueue(tracks, startIndex);

        if (isShuffle) {
          queue.setShuffleMode('on');
        }

        const currentTrack = queue.getCurrentItem();
        if (currentTrack) {
          await loadAndPlay(currentTrack);
        }
      },

      playAlbum: async (albumId, startIndex = 0) => {
        if (!currentClient) {
          throw new Error('Not authenticated');
        }

        const itemsService = new ItemsService(currentClient);
        const tracks = await itemsService.getAlbumTracks(albumId);

        if (tracks.length > 0) {
          await get().playTracks(tracks, startIndex);
        }
      },

      pause: () => {
        engine.pause();
        set({ isPlaying: false });

        if (playbackReporter && currentItemId) {
          playbackReporter.reportProgress(currentItemId, engine.getCurrentTime(), true).catch(() => {});
        }
      },

      resume: async () => {
        await engine.play();
        set({ isPlaying: true });

        if (playbackReporter && currentItemId) {
          playbackReporter.reportProgress(currentItemId, engine.getCurrentTime(), false).catch(() => {});
        }
      },

      next: async () => {
        const { queue, repeatMode } = get();

        let nextTrack: AudioItem | null = null;

        if (queue.hasNext()) {
          nextTrack = queue.next();
        } else if (repeatMode === 'all') {
          const allItems = queue.getAllItems();
          if (allItems.length > 0) {
            queue.setQueue(allItems, 0);
            nextTrack = queue.getCurrentItem();
          }
        }

        if (nextTrack) {
          await loadAndPlay(nextTrack);
        }
      },

      previous: async () => {
        const { queue } = get();
        const currentTime = engine.getCurrentTime();

        if (currentTime > 3) {
          engine.seek(0);
          return;
        }

        if (queue.hasPrevious()) {
          const prevTrack = queue.previous();
          if (prevTrack) {
            await loadAndPlay(prevTrack);
          }
        } else {
          engine.seek(0);
        }
      },

      seek: (seconds) => {
        engine.seek(seconds);
        useTimingStore.getState().updateTiming(seconds, engine.getDuration());

        if (playbackReporter && currentItemId) {
          playbackReporter.reportProgress(currentItemId, seconds, !get().isPlaying).catch(() => {});
        }
      },

      setVolume: (level) => {
        engine.setVolume(level);
        saveVolume(level);
        set({ volume: level });
      },

      toggleShuffle: () => {
        const { queue, isShuffle } = get();
        const newShuffle = !isShuffle;
        queue.setShuffleMode(newShuffle ? 'on' : 'off');
        set({ isShuffle: newShuffle });
      },

      toggleRepeat: () => {
        const { repeatMode } = get();
        const modes: RepeatMode[] = ['off', 'all', 'one'];
        const currentIndex = modes.indexOf(repeatMode);
        const nextMode = modes[(currentIndex + 1) % modes.length];
        set({ repeatMode: nextMode });
      },

      stop: () => {
        engine.pause();
        engine.seek(0);
        useTimingStore.getState().reset();

        if (playbackReporter && currentItemId) {
          playbackReporter.reportStopped(currentItemId, 0).catch(() => {});
          playbackReporter = null;
          currentItemId = null;
        }

        set({
          currentTrack: null,
          isPlaying: false,
          isLoading: false,
          error: null,
        });
      },
    };
  })
);

useAuthStore.subscribe(
  (state) => state.client,
  (client) => {
    currentClient = client;

    if (!client) {
      usePlayerStore.getState().stop();
    }
  }
);

export const useCurrentTrack = () => usePlayerStore((state) => state.currentTrack);
export const useIsPlaying = () => usePlayerStore((state) => state.isPlaying);
export const useIsLoading = () => usePlayerStore((state) => state.isLoading);
export const useVolume = () => usePlayerStore((state) => state.volume);
export const useIsShuffle = () => usePlayerStore((state) => state.isShuffle);
export const useRepeatMode = () => usePlayerStore((state) => state.repeatMode);
export const usePlayerError = () => usePlayerStore((state) => state.error);
