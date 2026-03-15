import { create } from 'zustand';
import { subscribeWithSelector } from 'zustand/middleware';
import { useShallow } from 'zustand/react/shallow';
import {
  PlaybackQueue,
  PlaybackReporter,
  ItemsService,
  type AudioItem,
  type MediaServerClient,
  type KaraokeStatus,
} from '@yay-tsa/core';
import {
  HTML5AudioEngine,
  MediaSessionManager,
  PinkNoiseGenerator,
  WakeLockManager,
  type AudioEngine,
} from '@yay-tsa/platform';
import { useAuthStore } from '@/features/auth/stores/auth.store';
import { log } from '@/shared/utils/logger';
import { useTimingStore } from './playback-timing.store';
import { PlaybackController, withTimeout } from './playback-controller';
import { PreloadManager } from './preload-manager';

export type RepeatMode = 'off' | 'one' | 'all';

interface PlayerState {
  queue: PlaybackQueue;
  queueItems: AudioItem[];
  queueIndex: number;
  currentTrack: AudioItem | null;
  isPlaying: boolean;
  isLoading: boolean;
  isShuffle: boolean;
  repeatMode: RepeatMode;
  volume: number;
  error: Error | null;
  isKaraokeMode: boolean;
  isKaraokeTransitioning: boolean;
  karaokeStatus: KaraokeStatus | null;
  karaokeEnabled: boolean;
  sleepTimerMinutes: number | null;
  sleepTimerEndTime: number | null;
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
  setShuffle: (enabled: boolean) => void;
  toggleRepeat: () => void;
  stop: () => void;
  toggleKaraoke: () => Promise<void>;
  refreshKaraokeStatus: () => Promise<void>;
  setSleepTimer: (minutes: number | null) => void;
  clearSleepTimer: () => void;
  updateCurrentTrackLyrics: (lyrics: string) => void;
  appendToQueue: (tracks: AudioItem[]) => void;
  jumpToQueueTrack: (trackId: string) => Promise<void>;
}

type PlayerStore = PlayerState & PlayerActions;

const VOLUME_STORAGE_KEY = 'yaytsa_volume';
const PROGRESS_REPORT_INTERVAL_MS = 10000;
const CROSSFADE_MS = 150;
const APPROACHING_END_MS = CROSSFADE_MS + 350;
const ENGINE_TIMEOUT_MS = 10000;

let audioEngine: AudioEngine | null = null;
let mediaSession: MediaSessionManager | null = null;
let playbackReporter: PlaybackReporter | null = null;
let currentItemId: string | null = null;
let currentClient: MediaServerClient | null = null;
let lastProgressReportTime = 0;

const wakeLock = new WakeLockManager();

let sleepTimerId: ReturnType<typeof setTimeout> | null = null;
const karaokeFailedTrackIds = new Set<string>();

function getAudioEngine(): AudioEngine | null {
  if (audioEngine) return audioEngine;
  try {
    audioEngine = new HTML5AudioEngine();
    return audioEngine;
  } catch (e) {
    log.player.error('Failed to initialize audio engine', e);
    return null;
  }
}

function getMediaSession(): MediaSessionManager | null {
  if (mediaSession) return mediaSession;
  try {
    mediaSession = new MediaSessionManager();
    return mediaSession;
  } catch (e) {
    log.player.warn('Failed to initialize media session', { error: String(e) });
    return null;
  }
}

function getSavedVolume(): number {
  try {
    const saved = localStorage.getItem(VOLUME_STORAGE_KEY);
    if (saved) {
      const parsed = Number.parseFloat(saved);
      if (!Number.isNaN(parsed) && parsed >= 0 && parsed <= 1) {
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

let consecutiveLoadFailures = 0;
const MAX_CONSECUTIVE_FAILURES = 3;

function startPlaybackReporter(trackId: string): void {
  if (!currentClient) return;
  lastProgressReportTime = 0;
  playbackReporter = new PlaybackReporter(currentClient);
  playbackReporter.reportStart(trackId).catch(err => {
    log.player.warn('Failed to report start', { error: String(err) });
  });
}

function resolveNextItem(queue: PlaybackQueue, repeatMode: RepeatMode): AudioItem | null {
  const next = queue.peekNext();
  if (next) return next;
  if (repeatMode === 'all' && !queue.isEmpty()) return queue.getItemAt(0);
  return null;
}

function autoAdvanceOnError(get: () => PlayerStore): void {
  consecutiveLoadFailures++;
  log.player.warn(`Track load failure ${consecutiveLoadFailures}/${MAX_CONSECUTIVE_FAILURES}`);

  if (consecutiveLoadFailures >= MAX_CONSECUTIVE_FAILURES) {
    consecutiveLoadFailures = 0;
    log.player.error('Max consecutive load failures reached, stopping playback');
    return;
  }

  const { queue, repeatMode } = get();
  const next = resolveNextItem(queue, repeatMode);
  if (next) {
    get().queue.advanceTo(next.Id);
    setTimeout(() => {
      void get().next();
    }, 0);
  }
}

const initialState: PlayerState = {
  queue: new PlaybackQueue(),
  queueItems: [],
  queueIndex: -1,
  currentTrack: null,
  isPlaying: false,
  isLoading: false,
  isShuffle: false,
  repeatMode: 'off',
  volume: getSavedVolume(),
  error: null,
  isKaraokeMode: false,
  isKaraokeTransitioning: false,
  karaokeStatus: null,
  karaokeEnabled: false,
  sleepTimerMinutes: null,
  sleepTimerEndTime: null,
};

export const usePlayerStore = create<PlayerStore>()(
  subscribeWithSelector((set, get) => {
    const maybeEngine = getAudioEngine();
    const session = getMediaSession();

    if (!maybeEngine) {
      log.player.error('Audio engine not available, player will not function');
      return {
        ...initialState,
        error: new Error('Audio playback is not supported in this browser'),
        playTrack: async () => {},
        playTracks: async () => {},
        playAlbum: async () => {},
        pause: () => {},
        resume: async () => {},
        next: async () => {},
        previous: async () => {},
        seek: () => {},
        setVolume: () => {},
        toggleShuffle: () => {},
        setShuffle: () => {},
        toggleRepeat: () => {},
        stop: () => {},
        toggleKaraoke: async () => {},
        refreshKaraokeStatus: async () => {},
        setSleepTimer: () => {},
        clearSleepTimer: () => {},
        updateCurrentTrackLyrics: () => {},
        appendToQueue: () => {},
        jumpToQueueTrack: async () => {},
      };
    }

    const engine = maybeEngine;
    engine.setVolume(initialState.volume);

    const controller = new PlaybackController();
    const preloader = new PreloadManager(engine);

    // Suppress spurious ended event after gapless transition (#4)
    let suppressNextEnded = false;

    // --- Helpers ---

    function syncQueueState(): void {
      const { queue } = get();
      set({ queueItems: queue.getAllItems(), queueIndex: queue.getCurrentIndex() });
    }

    function reportStopped(): void {
      if (playbackReporter && currentItemId) {
        const prevId = currentItemId;
        const prevPos = engine.getCurrentTime();
        playbackReporter.reportStopped(prevId, prevPos).catch(err => {
          log.player.warn('Failed to report stopped', { error: String(err) });
        });
      }
    }

    function updateSessionMetadata(track: AudioItem): void {
      if (!currentClient) return;
      let imageUrl: string | undefined;
      if (track.AlbumPrimaryImageTag) {
        imageUrl = currentClient.getImageUrl(track.AlbumId ?? track.Id, 'Primary', {
          tag: track.AlbumPrimaryImageTag,
          maxWidth: 256,
          maxHeight: 256,
        });
      } else if (track.AlbumId) {
        imageUrl = currentClient.getImageUrl(track.AlbumId, 'Primary', {
          maxWidth: 256,
          maxHeight: 256,
        });
      }

      session?.updateMetadata({
        title: track.Name,
        artist: track.Artists?.join(', ') ?? 'Unknown Artist',
        album: track.Album ?? 'Unknown Album',
        artwork: imageUrl,
      });
    }

    function schedulePreload(): void {
      if (!currentClient) return;
      const { queue, repeatMode } = get();
      const next = resolveNextItem(queue, repeatMode);
      if (next) {
        const url = new ItemsService(currentClient).getStreamUrl(next.Id);
        preloader.prepare(next.Id, url);
      } else {
        preloader.invalidate();
      }
    }

    async function applyReadyKaraokeState(
      trackId: string,
      status: KaraokeStatus,
      signal: AbortSignal
    ): Promise<void> {
      set({ isKaraokeTransitioning: true });
      const url = currentClient!.getInstrumentalStreamUrl(trackId);
      await karaokeSwitchUrl(url, signal);
      if (!signal.aborted) {
        set({ isKaraokeMode: true, karaokeStatus: status, isKaraokeTransitioning: false });
        preloader.invalidate();
        schedulePreload();
      }
    }

    async function applyNotStartedKaraokeState(
      trackId: string,
      signal: AbortSignal
    ): Promise<void> {
      if (karaokeFailedTrackIds.has(trackId)) {
        set({ isKaraokeMode: false, karaokeEnabled: false });
        return;
      }
      await currentClient!.requestKaraokeProcessing(trackId);
      if (!signal.aborted) {
        set({ isKaraokeMode: false, karaokeStatus: { state: 'PROCESSING', message: null } });
      }
    }

    async function syncKaraokeForTrack(track: AudioItem, signal: AbortSignal): Promise<void> {
      if (!currentClient) return;
      const trackId = track.Id;
      try {
        const status = await currentClient.getKaraokeStatus(trackId);
        if (signal.aborted) return;
        if (status.state === 'READY') {
          await applyReadyKaraokeState(trackId, status, signal);
        } else if (status.state === 'NOT_STARTED' && get().karaokeEnabled) {
          await applyNotStartedKaraokeState(trackId, signal);
        } else if (status.state === 'PROCESSING' && get().karaokeEnabled) {
          if (!signal.aborted) set({ isKaraokeMode: false, karaokeStatus: status });
        } else if (!signal.aborted) {
          if (status.state === 'FAILED') {
            set({ isKaraokeMode: false, karaokeEnabled: false, karaokeStatus: status });
          } else {
            set({ isKaraokeMode: false, karaokeStatus: null });
          }
        }
      } catch (error) {
        if (signal.aborted) return;
        const isAbort = error instanceof DOMException && error.name === 'AbortError';
        if (!isAbort) {
          log.player.warn('Failed to sync karaoke for track', { error: String(error) });
          set({
            isKaraokeMode: false,
            isKaraokeTransitioning: false,
            karaokeEnabled: false,
            karaokeStatus: null,
          });
        }
      }
    }

    async function karaokeSwitchUrl(url: string, signal: AbortSignal): Promise<void> {
      if (engine.preload && engine.seamlessSwitch) {
        const hint = engine.getCurrentTime();
        await withTimeout(engine.preload(url), ENGINE_TIMEOUT_MS);
        if (signal.aborted) return;
        const result = await withTimeout(engine.seamlessSwitch(hint, 50), ENGINE_TIMEOUT_MS);
        if (!get().isPlaying) engine.pause();
        if (result) {
          useTimingStore.getState().updateTiming(result.position, result.duration);
        }
      } else {
        const position = engine.getCurrentTime();
        await withTimeout(engine.load(url), ENGINE_TIMEOUT_MS);
        if (signal.aborted) return;
        engine.seek(position);
        if (get().isPlaying) await engine.play();
      }
    }

    // --- Core playback commands ---

    async function loadAndPlay(track: AudioItem, signal: AbortSignal): Promise<void> {
      if (!currentClient) throw new Error('Not authenticated');

      set({
        currentTrack: track,
        isLoading: true,
        error: null,
        isKaraokeMode: false,
        isKaraokeTransitioning: false,
        karaokeStatus: null,
      });
      reportStopped();

      try {
        const streamUrl = new ItemsService(currentClient).getStreamUrl(track.Id);

        await withTimeout(engine.load(streamUrl), ENGINE_TIMEOUT_MS);
        set({ currentTrack: track, isLoading: false, error: null });

        if (signal.aborted) return;

        await engine.play();
        if (signal.aborted) return;

        // --- Commit: identity + playing state ---
        currentItemId = track.Id;
        set({ isPlaying: true });
        consecutiveLoadFailures = 0;
        wakeLock.acquire().catch(() => {});

        // --- Side effects ---
        engine.setNormalizationGain?.(track.NormalizationGain ?? null);
        get().queue.advanceTo(track.Id);
        get().queue.trimBeforeCurrent();
        syncQueueState();
        updateSessionMetadata(track);
        startPlaybackReporter(track.Id);
        preloader.invalidate();
        schedulePreload();

        if (get().karaokeEnabled) {
          void syncKaraokeForTrack(track, signal);
        }
      } catch (error) {
        if (signal.aborted) return;
        const isEngineTimeout = error instanceof Error && error.message.includes('Engine timeout');
        if (isEngineTimeout && consecutiveLoadFailures < 2 && !signal.aborted) {
          log.player.warn('Engine timeout on load, retrying once after delay', {
            trackId: track.Id,
          });
          await new Promise<void>(resolve => {
            setTimeout(resolve, 2000);
          });
          if (!signal.aborted) {
            return loadAndPlay(track, signal);
          }
          return;
        }
        log.player.error('Failed to load and play track', error, {
          trackId: track.Id,
          trackName: track.Name,
        });
        set({
          error: error instanceof Error ? error : new Error(String(error)),
          isPlaying: false,
          isLoading: false,
        });
        throw error;
      }
    }

    async function gaplessTransition(
      track: AudioItem,
      instant: boolean,
      signal: AbortSignal
    ): Promise<void> {
      if (!currentClient) return;

      reportStopped();

      // --- Engine mutation (point of no return) ---
      let result;
      if (instant && engine.transitionToPreloaded) {
        result = await withTimeout(engine.transitionToPreloaded(), ENGINE_TIMEOUT_MS);
      } else {
        if (!engine.seamlessSwitch) throw new Error('seamlessSwitch not available');
        result = await withTimeout(engine.seamlessSwitch(0, CROSSFADE_MS), ENGINE_TIMEOUT_MS);
      }

      // --- Always: engine-level cleanup ---
      preloader.consume();
      suppressNextEnded = true;
      currentItemId = track.Id;

      if (signal.aborted) {
        set({ currentTrack: track, isLoading: false, error: null });
        return;
      }

      // --- Commit: state matches engine reality ---
      set({ currentTrack: track, isPlaying: true, isLoading: false, error: null });
      useTimingStore.getState().updateTiming(0, result.duration);

      // --- Side effects ---
      engine.setNormalizationGain?.(track.NormalizationGain ?? null);
      get().queue.advanceTo(track.Id);
      get().queue.trimBeforeCurrent();
      syncQueueState();
      updateSessionMetadata(track);
      startPlaybackReporter(track.Id);

      if (get().isKaraokeMode || get().karaokeEnabled) {
        await syncKaraokeForTrack(track, signal);
      } else if (!signal.aborted) {
        set({ karaokeStatus: null });
      }

      schedulePreload();
    }

    // --- Engine event handlers ---

    engine.onTimeUpdate(seconds => {
      const duration = engine.getDuration();
      useTimingStore.getState().updateTiming(seconds, duration);

      const now = Date.now();
      if (
        playbackReporter &&
        currentItemId &&
        now - lastProgressReportTime >= PROGRESS_REPORT_INTERVAL_MS
      ) {
        lastProgressReportTime = now;
        playbackReporter.reportProgress(currentItemId, seconds, false).catch(err => {
          log.player.warn('Failed to report playback progress', { error: String(err) });
        });
      }
    });

    engine.onApproachingEnd?.(() => {
      void controller.ifIdle(async signal => {
        const { queue, repeatMode } = get();
        if (repeatMode === 'one') return;

        const next = resolveNextItem(queue, repeatMode);
        if (!next || !preloader.isReady(next.Id)) return;

        await gaplessTransition(next, false, signal);
      });
    }, APPROACHING_END_MS);

    engine.onEnded(() => {
      if (suppressNextEnded) {
        suppressNextEnded = false;
        return;
      }

      void controller.ifIdle(async signal => {
        const { repeatMode } = get();

        if (repeatMode === 'one') {
          engine.seek(0);
          await engine.play();
          return;
        }

        const next = resolveNextItem(get().queue, repeatMode);
        if (!next) {
          engine.pause();
          wakeLock.release();
          if (playbackReporter && currentItemId) {
            playbackReporter.reportStopped(currentItemId, engine.getCurrentTime()).catch(err => {
              log.player.warn('Failed to report stop on natural end', { error: String(err) });
            });
            playbackReporter = null;
            currentItemId = null;
          }
          useTimingStore.getState().reset();
          set({ isPlaying: false });
          return;
        }

        if (preloader.isReady(next.Id)) {
          await gaplessTransition(next, true, signal);
        } else {
          get().queue.advanceTo(next.Id);
          try {
            await loadAndPlay(next, signal);
          } catch {
            if (!signal.aborted) autoAdvanceOnError(get);
          }
        }
      });
    });

    engine.onLoading(isLoading => {
      set({ isLoading });
    });

    engine.onError(error => {
      log.player.error('Audio engine error', error);
      set({ error, isPlaying: false, isLoading: false });
    });

    session?.setActionHandlers({
      onPlay: () => {
        get()
          .resume()
          .catch(() => {});
      },
      onPause: () => get().pause(),
      onSeek: seconds => get().seek(seconds),
      onNext: () => {
        get()
          .next()
          .catch(() => {});
      },
      onPrevious: () => {
        get()
          .previous()
          .catch(() => {});
      },
    });

    async function enableKaraokeMode(currentTrack: AudioItem, signal: AbortSignal): Promise<void> {
      set({ karaokeEnabled: true, isKaraokeTransitioning: true });
      const status = await currentClient!.getKaraokeStatus(currentTrack.Id);
      if (signal.aborted) return;

      if (status.state === 'NOT_STARTED') {
        if (karaokeFailedTrackIds.has(currentTrack.Id)) {
          set({ karaokeEnabled: false, isKaraokeTransitioning: false });
          return;
        }
        await currentClient!.requestKaraokeProcessing(currentTrack.Id);
        set({
          karaokeStatus: { state: 'PROCESSING', message: null },
          isKaraokeTransitioning: false,
        });
        return;
      }

      if (status.state === 'PROCESSING') {
        set({ karaokeStatus: status, isKaraokeTransitioning: false });
        return;
      }

      if (status.state === 'READY') {
        set({ isKaraokeMode: true, karaokeStatus: status });
        const instrumentalUrl = currentClient!.getInstrumentalStreamUrl(currentTrack.Id);
        await karaokeSwitchUrl(instrumentalUrl, signal);
        if (signal.aborted) return;
        preloader.invalidate();
        schedulePreload();
        set({ isKaraokeTransitioning: false });
        return;
      }

      // FAILED
      karaokeFailedTrackIds.add(currentTrack.Id);
      set({ karaokeEnabled: false, karaokeStatus: status, isKaraokeTransitioning: false });
    }

    async function disableKaraokeMode(currentTrack: AudioItem, signal: AbortSignal): Promise<void> {
      set({ isKaraokeMode: false, isKaraokeTransitioning: true, karaokeEnabled: false });
      const streamUrl = new ItemsService(currentClient!).getStreamUrl(currentTrack.Id);
      await karaokeSwitchUrl(streamUrl, signal);
      if (signal.aborted) return;
      preloader.invalidate();
      schedulePreload();
      set({ isKaraokeTransitioning: false, karaokeStatus: null });
    }

    return {
      ...initialState,

      playTrack: async track => {
        await controller.interrupt(async signal => {
          const { queue } = get();
          queue.setQueue([track], 0);
          await loadAndPlay(track, signal);
        });
      },

      playTracks: async (tracks, startIndex = 0) => {
        if (tracks.length === 0) return;

        await controller.interrupt(async signal => {
          const { queue, isShuffle, repeatMode } = get();
          queue.setQueue(tracks, startIndex);
          queue.setRepeatMode(repeatMode);
          if (isShuffle) {
            queue.setShuffleMode('on');
          }
          const currentTrack = queue.getCurrentItem();
          if (currentTrack) {
            await loadAndPlay(currentTrack, signal);
          }
        });
      },

      playAlbum: async (albumId, startIndex = 0) => {
        await controller.interrupt(async signal => {
          if (!currentClient) throw new Error('Not authenticated');
          const itemsService = new ItemsService(currentClient);
          const tracks = await itemsService.getAlbumTracks(albumId);
          if (signal.aborted || tracks.length === 0) return;
          const { queue, isShuffle, repeatMode } = get();
          queue.setQueue(tracks, startIndex);
          queue.setRepeatMode(repeatMode);
          if (isShuffle) {
            queue.setShuffleMode('on');
          }
          const currentTrack = queue.getCurrentItem();
          if (currentTrack) {
            await loadAndPlay(currentTrack, signal);
          }
        });
      },

      pause: () => {
        // eslint-disable-next-line @typescript-eslint/require-await
        void controller.interrupt(async () => {
          engine.pause();
          set({ isPlaying: false });
          wakeLock.release();

          if (playbackReporter && currentItemId) {
            playbackReporter
              .reportProgress(currentItemId, engine.getCurrentTime(), true)
              .catch(err => {
                log.player.warn('Failed to report pause', { error: String(err) });
              });
          }
        });
      },

      resume: async () => {
        await controller.interrupt(async () => {
          try {
            await engine.play();
            set({ isPlaying: true });
            wakeLock.acquire().catch(() => {});

            if (playbackReporter && currentItemId) {
              playbackReporter
                .reportProgress(currentItemId, engine.getCurrentTime(), false)
                .catch(err => {
                  log.player.warn('Failed to report resume', { error: String(err) });
                });
            }
          } catch (error) {
            log.player.warn('Resume failed', { error: String(error) });
            set({ isPlaying: false });
          }
        });
      },

      next: async () => {
        await controller.interrupt(async signal => {
          const { queue, repeatMode } = get();
          const next = resolveNextItem(queue, repeatMode);
          if (!next) {
            engine.pause();
            wakeLock.release();
            if (playbackReporter && currentItemId) {
              playbackReporter.reportStopped(currentItemId, engine.getCurrentTime()).catch(err => {
                log.player.warn('Failed to report stop on queue end', { error: String(err) });
              });
              playbackReporter = null;
              currentItemId = null;
            }
            useTimingStore.getState().reset();
            set({ isPlaying: false });
            return;
          }
          get().queue.advanceTo(next.Id);
          try {
            await loadAndPlay(next, signal);
          } catch {
            if (!signal.aborted) autoAdvanceOnError(get);
          }
        });
      },

      previous: async () => {
        await controller.interrupt(async signal => {
          const currentTime = engine.getCurrentTime();
          if (currentTime > 3) {
            engine.seek(0);
            return;
          }

          const { queue } = get();
          if (queue.hasPrevious()) {
            const prevTrack = queue.previous();
            if (prevTrack) {
              await loadAndPlay(prevTrack, signal);
            }
          } else {
            engine.seek(0);
          }
        });
      },

      seek: seconds => {
        if (controller.isActive) return;
        engine.seek(seconds);
        useTimingStore.getState().updateTiming(seconds, engine.getDuration());

        if (playbackReporter && currentItemId) {
          playbackReporter.reportProgress(currentItemId, seconds, !get().isPlaying).catch(err => {
            log.player.warn('Failed to report seek', { error: String(err) });
          });
        }
      },

      setVolume: level => {
        engine.setVolume(level);
        saveVolume(level);
        set({ volume: level });
      },

      toggleShuffle: () => {
        const { queue, isShuffle } = get();
        const newShuffle = !isShuffle;
        queue.setShuffleMode(newShuffle ? 'on' : 'off');
        set({ isShuffle: newShuffle });
        syncQueueState();
        preloader.invalidate();
        schedulePreload();
      },

      setShuffle: (enabled: boolean) => {
        const { queue, isShuffle } = get();
        if (isShuffle === enabled) return;
        queue.setShuffleMode(enabled ? 'on' : 'off');
        set({ isShuffle: enabled });
        syncQueueState();
        preloader.invalidate();
        schedulePreload();
      },

      toggleRepeat: () => {
        const { queue, repeatMode } = get();
        const modes: RepeatMode[] = ['off', 'all', 'one'];
        const currentIndex = modes.indexOf(repeatMode);
        const nextMode = modes[(currentIndex + 1) % modes.length] ?? 'off';
        queue.setRepeatMode(nextMode);
        set({ repeatMode: nextMode });
        preloader.invalidate();
        schedulePreload();
      },

      stop: () => {
        // eslint-disable-next-line @typescript-eslint/require-await
        void controller.interrupt(async () => {
          engine.pause();
          engine.seek(0);
          useTimingStore.getState().reset();
          preloader.invalidate();
          wakeLock.release();

          if (playbackReporter && currentItemId) {
            playbackReporter.reportStopped(currentItemId, 0).catch(err => {
              log.player.warn('Failed to report stop', { error: String(err) });
            });
            playbackReporter = null;
            currentItemId = null;
          }

          if (sleepTimerId) {
            clearTimeout(sleepTimerId);
            sleepTimerId = null;
          }

          const { queue: currentQueue } = get();
          currentQueue.setQueue([], 0);

          set({
            currentTrack: null,
            isPlaying: false,
            isLoading: false,
            error: null,
            isKaraokeMode: false,
            isKaraokeTransitioning: false,
            karaokeEnabled: false,
            karaokeStatus: null,
            sleepTimerMinutes: null,
            sleepTimerEndTime: null,
            queueItems: [],
            queueIndex: -1,
          });
          karaokeFailedTrackIds.clear();
        });
      },

      toggleKaraoke: async () => {
        const { currentTrack, isKaraokeMode, isKaraokeTransitioning } = get();
        if (!currentTrack || !currentClient || isKaraokeTransitioning) return;

        if (get().karaokeStatus?.state === 'PROCESSING') {
          set({ karaokeEnabled: false, karaokeStatus: null });
          return;
        }

        await controller.ifIdle(async signal => {
          try {
            if (!isKaraokeMode) {
              await enableKaraokeMode(currentTrack, signal);
            } else {
              await disableKaraokeMode(currentTrack, signal);
            }
          } catch (error) {
            if (signal.aborted) return;
            const isAbort = error instanceof DOMException && error.name === 'AbortError';
            if (isAbort) {
              log.player.warn('Karaoke enable interrupted by track change');
            } else {
              log.player.error('Failed to enable karaoke', error);
            }
            set({
              isKaraokeMode: false,
              isKaraokeTransitioning: false,
              karaokeEnabled: isAbort ? get().karaokeEnabled : false,
              karaokeStatus: null,
              error:
                !isAbort && error instanceof Error
                  ? new Error(`Couldn't load instrumental track — karaoke unavailable`)
                  : null,
            });
          }
        });
      },

      refreshKaraokeStatus: async () => {
        const { currentTrack, karaokeStatus } = get();
        if (!currentTrack || !currentClient) return;
        if (karaokeStatus?.state !== 'PROCESSING') return;

        let status: KaraokeStatus;
        try {
          status = await currentClient.getKaraokeStatus(currentTrack.Id);
        } catch {
          return;
        }

        const { currentTrack: ct, karaokeStatus: ks } = get();
        if (ct?.Id !== currentTrack.Id || ks?.state !== 'PROCESSING') return;
        if (!get().karaokeEnabled) return;

        if (status.state === 'FAILED') {
          set({ karaokeEnabled: false, karaokeStatus: status, isKaraokeMode: false });
          return;
        }

        if (status.state !== 'READY') {
          set({ karaokeStatus: status });
          return;
        }

        set({ karaokeStatus: status });

        await controller.ifIdle(async signal => {
          const { currentTrack: ct2, karaokeStatus: ks2 } = get();
          if (ct2?.Id !== currentTrack.Id || ks2?.state !== 'READY') return;

          set({ isKaraokeTransitioning: true, isKaraokeMode: true });
          const instrumentalUrl = currentClient!.getInstrumentalStreamUrl(ct2.Id);
          await karaokeSwitchUrl(instrumentalUrl, signal);
          if (signal.aborted) return;
          preloader.invalidate();
          schedulePreload();
          set({ isKaraokeTransitioning: false });
        });
      },

      setSleepTimer: (minutes: number | null) => {
        if (sleepTimerId) {
          clearTimeout(sleepTimerId);
          sleepTimerId = null;
        }

        if (minutes === null) {
          set({ sleepTimerMinutes: null, sleepTimerEndTime: null });
          return;
        }

        const endTime = Date.now() + minutes * 60 * 1000;
        set({ sleepTimerMinutes: minutes, sleepTimerEndTime: endTime });

        sleepTimerId = setTimeout(
          () => {
            get().pause();
            set({ sleepTimerMinutes: null, sleepTimerEndTime: null });
            sleepTimerId = null;
            log.player.info('Sleep timer triggered, playback paused');
          },
          minutes * 60 * 1000
        );
      },

      clearSleepTimer: () => {
        if (sleepTimerId) {
          clearTimeout(sleepTimerId);
          sleepTimerId = null;
        }
        cleanupCustomSleepTimer();
        set({ sleepTimerMinutes: null, sleepTimerEndTime: null });
      },

      updateCurrentTrackLyrics: (lyrics: string) => {
        const { currentTrack } = get();
        if (currentTrack) {
          set({ currentTrack: { ...currentTrack, Lyrics: lyrics } });
        }
      },

      appendToQueue: (tracks: AudioItem[]) => {
        if (tracks.length === 0) return;
        const { queue } = get();
        queue.addMultipleToQueue(tracks);
        syncQueueState();
        preloader.invalidate();
        schedulePreload();
      },

      jumpToQueueTrack: async (trackId: string) => {
        await controller.interrupt(async signal => {
          const { queue } = get();
          const items = queue.getAllItems();
          const target = items.find(item => item.Id === trackId);
          if (!target) return;
          const targetIndex = items.indexOf(target);
          queue.advanceTo(trackId);
          queue.trimBeforeCurrent();
          syncQueueState();
          try {
            await loadAndPlay(target, signal);
          } catch {
            if (!signal.aborted) autoAdvanceOnError(get);
            return;
          }

          if (signal.aborted) return;

          const { useSessionStore } = await import('./session-store');
          const sessionState = useSessionStore.getState();
          if (sessionState.activeSession) {
            const remaining = items.length - targetIndex - 1;
            const hour = new Date().getHours();
            let timeOfDay: string;
            if (hour < 6) timeOfDay = 'night';
            else if (hour < 12) timeOfDay = 'morning';
            else if (hour < 18) timeOfDay = 'afternoon';
            else timeOfDay = 'evening';
            sessionState
              .sendSignal({
                signalType: 'QUEUE_JUMP',
                trackId,
                context: {
                  positionPct: 0,
                  elapsedSec: 0,
                  autoplay: false,
                  selectedByUser: true,
                  timeOfDay,
                },
              })
              .catch(() => {});
            if (remaining < 8) {
              sessionState.refreshQueue().catch(() => {});
            }
          }
        });
      },
    };
  })
);

useAuthStore.subscribe(
  state => state.client,
  client => {
    currentClient = client;

    if (!client) {
      usePlayerStore.getState().stop();
    }
  }
);

type SleepTimerPhase = 'idle' | 'music' | 'crossfade-to-noise' | 'noise' | 'stopped';

let sleepTimerPhase: SleepTimerPhase = 'idle';
let sleepTimerCrossfadeInterval: ReturnType<typeof setInterval> | null = null;
let sleepTimerOriginalVolume: number | null = null;
let sleepTimerFadeCancel: (() => void) | null = null;

function cleanupCustomSleepTimer() {
  if (sleepTimerCrossfadeInterval) {
    clearInterval(sleepTimerCrossfadeInterval);
    sleepTimerCrossfadeInterval = null;
  }
  if (sleepTimerFadeCancel) {
    sleepTimerFadeCancel();
    sleepTimerFadeCancel = null;
  }
  if (sleepTimerOriginalVolume !== null && audioEngine) {
    audioEngine.setVolume(sleepTimerOriginalVolume);
    sleepTimerOriginalVolume = null;
  }
  sleepTimerPhase = 'idle';
}

function completeSleepTimer() {
  usePlayerStore.getState().pause();
  if (sleepTimerOriginalVolume !== null && audioEngine) {
    audioEngine.setVolume(sleepTimerOriginalVolume);
  }
  sleepTimerOriginalVolume = null;
  sleepTimerPhase = 'stopped';
  usePlayerStore.setState({ sleepTimerEndTime: null, sleepTimerMinutes: null });
}

declare global {
  var __playerStore__:
    | {
        readonly isPlaying: boolean;
        readonly volume: number;
        readonly currentTrack: AudioItem | null;
        readonly audioEngine: AudioEngine | null;
        readonly mediaSession: MediaSessionManager | null;
        setVolume: (level: number) => void;
      }
    | undefined;
  var __sleepTimerStore__:
    | {
        readonly isActive: boolean;
        readonly remainingMs: number;
        readonly phase: SleepTimerPhase;
        startCustomTimer: (config: {
          musicDurationMs: number;
          crossfadeDurationMs: number;
          noiseDurationMs: number;
        }) => void;
      }
    | undefined;
  var __platformClasses__:
    | {
        PinkNoiseGenerator: typeof PinkNoiseGenerator;
        MediaSessionManager: typeof MediaSessionManager;
        HTML5AudioEngine: typeof HTML5AudioEngine;
      }
    | undefined;
}

if (import.meta.env.VITE_TEST_MODE === 'true' || import.meta.env.DEV) {
  globalThis.__playerStore__ = {
    get isPlaying() {
      return usePlayerStore.getState().isPlaying;
    },
    get volume() {
      return usePlayerStore.getState().volume;
    },
    get currentTrack() {
      return usePlayerStore.getState().currentTrack;
    },
    get audioEngine() {
      return audioEngine;
    },
    get mediaSession() {
      return mediaSession;
    },
    setVolume(level: number) {
      usePlayerStore.getState().setVolume(level);
    },
  };

  globalThis.__sleepTimerStore__ = {
    get isActive() {
      return usePlayerStore.getState().sleepTimerEndTime !== null || sleepTimerPhase !== 'idle';
    },
    get remainingMs() {
      const endTime = usePlayerStore.getState().sleepTimerEndTime;
      if (endTime === null) return 0;
      return Math.max(0, endTime - Date.now());
    },
    get phase(): SleepTimerPhase {
      if (sleepTimerPhase !== 'idle') return sleepTimerPhase;
      const endTime = usePlayerStore.getState().sleepTimerEndTime;
      if (endTime !== null) return 'music';
      return 'idle';
    },
    startCustomTimer({ musicDurationMs, crossfadeDurationMs, noiseDurationMs }) {
      cleanupCustomSleepTimer();

      if (sleepTimerId) {
        clearTimeout(sleepTimerId);
        sleepTimerId = null;
      }
      usePlayerStore.getState().clearSleepTimer();

      sleepTimerOriginalVolume = audioEngine?.getVolume() ?? 1;
      sleepTimerPhase = 'music';

      const totalMs = musicDurationMs + crossfadeDurationMs + noiseDurationMs;
      const endTime = Date.now() + totalMs;
      usePlayerStore.setState({ sleepTimerEndTime: endTime, sleepTimerMinutes: totalMs / 60000 });

      setTimeout(() => {
        sleepTimerPhase = 'crossfade-to-noise';
        const startVolume = audioEngine?.getVolume() ?? sleepTimerOriginalVolume ?? 1;

        if (audioEngine?.fadeVolume) {
          const { promise, cancel } = audioEngine.fadeVolume(startVolume, 0, crossfadeDurationMs);
          sleepTimerFadeCancel = cancel;
          promise
            .then(() => {
              sleepTimerFadeCancel = null;
              if (noiseDurationMs > 0) {
                sleepTimerPhase = 'noise';
                setTimeout(() => {
                  completeSleepTimer();
                }, noiseDurationMs);
              } else {
                completeSleepTimer();
              }
            })
            .catch(() => {});
        } else {
          completeSleepTimer();
        }
      }, musicDurationMs);
    },
  };

  globalThis.__platformClasses__ = {
    PinkNoiseGenerator,
    MediaSessionManager,
    HTML5AudioEngine,
  };
}

export const useCurrentTrack = () => usePlayerStore(state => state.currentTrack);
export const useIsPlaying = () => usePlayerStore(state => state.isPlaying);
export const useIsLoading = () => usePlayerStore(state => state.isLoading);
export const useVolume = () => usePlayerStore(state => state.volume);
export const useIsShuffle = () => usePlayerStore(state => state.isShuffle);
export const useRepeatMode = () => usePlayerStore(state => state.repeatMode);
export const usePlayerError = () => usePlayerStore(state => state.error);
export const useIsKaraokeMode = () => usePlayerStore(state => state.isKaraokeMode);
export const useIsKaraokeTransitioning = () =>
  usePlayerStore(state => state.isKaraokeTransitioning);
export const useKaraokeEnabled = () => usePlayerStore(state => state.karaokeEnabled);
export const useKaraokeStatus = () => usePlayerStore(state => state.karaokeStatus);
export const useSleepTimer = () =>
  usePlayerStore(
    useShallow(state => ({
      minutes: state.sleepTimerMinutes,
      endTime: state.sleepTimerEndTime,
    }))
  );
export const useQueueItems = () => usePlayerStore(state => state.queueItems);
export const useQueueIndex = () => usePlayerStore(state => state.queueIndex);
