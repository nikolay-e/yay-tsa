import { create } from 'zustand';
import { subscribeWithSelector } from 'zustand/middleware';
import { useShallow } from 'zustand/react/shallow';
import {
  PlaybackQueue,
  PlaybackReporter,
  ItemsService,
  setItemFavorite,
  isAudiobook,
  resumePositionSeconds,
  getSmartRewindMs,
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
import { useOfflineStore } from '@/features/offline/stores/offline.store';
import { writeLocalResume, bumpResumeVersion } from '@/features/audiobooks/stores/local-resume';
import { log } from '@/shared/utils/logger';
import { currentTimeOfDay } from '@/shared/utils/time';
import { useTimingStore } from './playback-timing.store';
import { PlaybackController, withTimeout } from './playback-controller';
import { PreloadManager } from './preload-manager';
import { navAvailability, previousAction, endedAction } from './playback-decisions';

export type RepeatMode = 'off' | 'one' | 'all';
export type PlayerMode = 'music' | 'audiobook';
export type SpeedScope = 'book' | 'all';

const AUDIOBOOK_SPEED_KEY = 'yaytsa_audiobook_speed';
const bookSpeedKey = (bookId: string) => `yaytsa_book_speed_${bookId}`;

function clampRate(rate: number): number {
  return Math.max(0.5, Math.min(3, rate));
}

function loadGlobalAudiobookSpeed(): number {
  try {
    const raw = localStorage.getItem(AUDIOBOOK_SPEED_KEY);
    return raw ? clampRate(Number.parseFloat(raw)) : 1;
  } catch {
    return 1;
  }
}

function resolveAudiobookSpeed(bookId: string): number {
  try {
    const perBook = localStorage.getItem(bookSpeedKey(bookId));
    if (perBook) return clampRate(Number.parseFloat(perBook));
  } catch {
    // Ignore storage errors
  }
  return loadGlobalAudiobookSpeed();
}

function persistAudiobookSpeed(rate: number, scope: SpeedScope, bookId: string | null): void {
  try {
    if (scope === 'all') {
      localStorage.setItem(AUDIOBOOK_SPEED_KEY, rate.toString());
    } else if (bookId) {
      localStorage.setItem(bookSpeedKey(bookId), rate.toString());
    }
  } catch {
    // Ignore storage errors
  }
}

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
  playerMode: PlayerMode;
  playbackRate: number;
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
  skipBy: (deltaSeconds: number) => void;
  setVolume: (level: number) => void;
  toggleShuffle: () => void;
  setShuffle: (enabled: boolean) => void;
  toggleRepeat: () => void;
  stop: () => void;
  toggleKaraoke: () => Promise<void>;
  refreshKaraokeStatus: () => Promise<void>;
  setSleepTimer: (minutes: number | null) => void;
  clearSleepTimer: () => void;
  setPlaybackRate: (rate: number, scope?: SpeedScope) => void;
  updateCurrentTrackLyrics: (lyrics: string) => void;
  appendToQueue: (tracks: AudioItem[]) => void;
  insertNextInQueue: (tracks: AudioItem[]) => void;
  removeFromQueue: (trackId: string) => void;
  jumpToQueueTrack: (trackId: string) => Promise<void>;
  patchTrackFavorite: (itemId: string, isFavorite: boolean) => void;
}

type PlayerStore = PlayerState & PlayerActions;

const VOLUME_STORAGE_KEY = 'yaytsa_volume';
const PROGRESS_REPORT_INTERVAL_MS = 10000;
// Audiobooks report to the server far more often than music: a lost minute of a 10-hour book is a
// real regression, whereas a song restarts cheaply. Local-first write-through is the primary
// durability layer; this tighter network heartbeat keeps cross-device resume fresh.
const AUDIOBOOK_PROGRESS_REPORT_INTERVAL_MS = 2000;
const LOCAL_RESUME_WRITE_INTERVAL_MS = 1000;
const CROSSFADE_MS = 150;
const APPROACHING_END_MS = CROSSFADE_MS + 350;
const ENGINE_TIMEOUT_MS = 10000;

let audioEngine: AudioEngine | null = null;
let mediaSession: MediaSessionManager | null = null;
let playbackReporter: PlaybackReporter | null = null;
let currentItemId: string | null = null;
let currentClient: MediaServerClient | null = null;
let lastProgressReportTime = 0;
let lastLocalResumeWriteTime = 0;

const wakeLock = new WakeLockManager();

let sleepTimerId: ReturnType<typeof setTimeout> | null = null;
const karaokeFailedTrackIds = new Set<string>();

function isRetryableTimeout(error: unknown, retryCount: number): boolean {
  return error instanceof Error && error.message.includes('Engine timeout') && retryCount < 1;
}

function getAudioEngine(): AudioEngine | null {
  if (audioEngine) return audioEngine;
  try {
    audioEngine = new HTML5AudioEngine({ approachingEndThresholdMs: APPROACHING_END_MS });
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

function resolveNextItem(queue: PlaybackQueue, _repeatMode: RepeatMode): AudioItem | null {
  return queue.peekNext();
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
  playerMode: 'music',
  playbackRate: 1,
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
        skipBy: () => {},
        setVolume: () => {},
        toggleShuffle: () => {},
        setShuffle: () => {},
        toggleRepeat: () => {},
        stop: () => {},
        toggleKaraoke: async () => {},
        refreshKaraokeStatus: async () => {},
        setSleepTimer: () => {},
        clearSleepTimer: () => {},
        setPlaybackRate: () => {},
        updateCurrentTrackLyrics: () => {},
        appendToQueue: () => {},
        insertNextInQueue: () => {},
        removeFromQueue: () => {},
        jumpToQueueTrack: async () => {},
        patchTrackFavorite: () => {},
      };
    }

    const engine = maybeEngine;
    engine.setVolume(initialState.volume);

    const controller = new PlaybackController();
    const preloader = new PreloadManager(engine);

    // Suppress spurious ended event after gapless transition (#4)
    let suppressNextEnded = false;
    let pendingSeek: number | null = null;
    // Wall-clock instant of the last pause, used to compute audiobook smart-rewind on resume.
    let lastPauseAt: number | null = null;

    // --- Helpers ---

    function syncQueueState(): void {
      const { queue } = get();
      set({ queueItems: queue.getAllItems(), queueIndex: queue.getCurrentIndex() });
      updateMediaSessionNav();
    }

    // Keep the lock-screen next/previous controls in sync with the queue. nexttrack is removed when
    // there is genuinely nothing next (last track, repeat off) so the OS doesn't show a dead button;
    // previoustrack stays available whenever a track is loaded because previous() restarts the
    // current track when >3s in, and steps back otherwise.
    function updateMediaSessionNav(): void {
      if (!session) return;
      const { hasNext, hasPrevious } = navAvailability(get().queue);
      session.setNavigationHandlers(
        hasNext
          ? () => {
              get()
                .next()
                .catch(() => {});
            }
          : null,
        hasPrevious
          ? () => {
              get()
                .previous()
                .catch(() => {});
            }
          : null
      );
    }

    function reportStopped(): void {
      if (playbackReporter && currentItemId) {
        const prevId = currentItemId;
        const prevPos = engine.getCurrentTime();
        // Local-first write-through for the outgoing chapter so a chapter change never loses its
        // place even if the network report is dropped.
        if (get().playerMode === 'audiobook') {
          writeLocalResume(prevId, prevPos, engine.getDuration());
          bumpResumeVersion();
        }
        // Persist the resume position into the local offline record (no-op when the track
        // is not downloaded) so an offline cold start can restore the place.
        void useOfflineStore.getState().saveResume(prevId, prevPos);
        // Offline: the network report will fail, so queue the resume position to
        // sync on reconnect. Online failures are transient and logged only.
        if (typeof navigator !== 'undefined' && !navigator.onLine) {
          useOfflineStore
            .getState()
            .queueProgress(prevId, prevPos)
            .catch(() => {});
        }
        playbackReporter.reportStopped(prevId, prevPos).catch(err => {
          log.player.warn('Failed to report stopped', { error: String(err) });
        });
      }
    }

    // Write-through the current audiobook position to localStorage. Cheap, synchronous, and the
    // primary durability layer — independent of the network and the server cache.
    function writeLocalResumeNow(): void {
      if (get().playerMode !== 'audiobook' || !currentItemId) return;
      writeLocalResume(currentItemId, engine.getCurrentTime(), engine.getDuration());
    }

    // Best-effort durable flush for page unload / backgrounding: keepalive network write plus the
    // local write-through, so closing the tab mid-chapter never loses the place.
    function flushResumeOnHide(): void {
      writeLocalResumeNow();
      if (playbackReporter && currentItemId) {
        playbackReporter.flushProgress(currentItemId, engine.getCurrentTime(), !get().isPlaying);
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

    function syncMediaSessionPlayback(
      state: 'none' | 'paused' | 'playing',
      positionOverride?: number
    ): void {
      session?.setPlaybackState(state);
      if (state === 'none') return;
      const position = positionOverride ?? engine.getCurrentTime();
      session?.updatePositionState(engine.getDuration(), position);
    }

    function schedulePreload(): void {
      if (!currentClient) return;
      const { queue, repeatMode, isKaraokeMode } = get();
      if (isKaraokeMode) {
        preloader.invalidate();
        return;
      }
      const next = resolveNextItem(queue, repeatMode);
      if (next) {
        const networkUrl = new ItemsService(currentClient).getStreamUrl(next.Id);
        // Prefer a downloaded blob; resolution is async, so re-check the queue
        // hasn't moved on before arming the preloader for a now-stale track.
        void useOfflineStore
          .getState()
          .getPlaybackUrl(next.Id, networkUrl)
          .then(url => {
            if (resolveNextItem(get().queue, get().repeatMode)?.Id === next.Id) {
              preloader.prepare(next.Id, url);
            }
          });
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
      try {
        if (!currentClient) return;
        const url = currentClient.getInstrumentalStreamUrl(trackId);
        await karaokeSwitchUrl(url, signal);
        if (!signal.aborted) {
          set({ isKaraokeMode: true, karaokeStatus: status, isKaraokeTransitioning: false });
          preloader.invalidate();
          schedulePreload();
        }
      } finally {
        set({ isKaraokeTransitioning: false });
      }
    }

    async function syncKaraokeForTrack(track: AudioItem, signal: AbortSignal): Promise<void> {
      if (!currentClient) return;
      const trackId = track.Id;
      try {
        const status = await currentClient.getKaraokeStatus(trackId);
        if (signal.aborted || get().currentTrack?.Id !== trackId) return;

        if (status.state === 'READY') {
          await applyReadyKaraokeState(trackId, status, signal);
        } else if (status.state === 'PROCESSING' && get().karaokeEnabled) {
          set({ isKaraokeMode: false, karaokeStatus: status });
        } else if (status.state === 'FAILED') {
          set({ isKaraokeMode: false, karaokeEnabled: false, karaokeStatus: status });
        } else {
          set({ isKaraokeMode: false, karaokeStatus: null });
        }
      } catch (error) {
        if (signal.aborted || get().currentTrack?.Id !== trackId) return;
        const isAbort = error instanceof DOMException && error.name === 'AbortError';
        if (!isAbort) {
          log.player.warn('Failed to sync karaoke for track', { error: String(error) });
          set({ isKaraokeMode: false, isKaraokeTransitioning: false, karaokeStatus: null });
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

    function commitPlaybackSideEffects(track: AudioItem, signal: AbortSignal): void {
      currentItemId = track.Id;
      set({ isPlaying: true });
      consecutiveLoadFailures = 0;
      wakeLock.acquire().catch(() => {});

      engine.setNormalizationGain?.(track.NormalizationGain ?? null);
      get().queue.advanceTo(track.Id);
      syncQueueState();
      updateSessionMetadata(track);
      syncMediaSessionPlayback('playing', engine.getCurrentTime());
      startPlaybackReporter(track.Id);
      preloader.invalidate();
      schedulePreload();
      void useOfflineStore.getState().cachePlayed(track);

      if (get().karaokeEnabled && !get().isKaraokeTransitioning) {
        void syncKaraokeForTrack(track, signal);
      }
    }

    async function handleLoadError(
      error: unknown,
      track: AudioItem,
      signal: AbortSignal,
      retryCount: number,
      resumeFromSaved: boolean
    ): Promise<void> {
      if (signal.aborted) return;
      if (isRetryableTimeout(error, retryCount)) {
        log.player.warn('Engine timeout on load, retrying once after delay', {
          trackId: track.Id,
        });
        await new Promise<void>(resolve => {
          setTimeout(resolve, 2000);
        });
        if (!signal.aborted) {
          return loadAndPlay(track, signal, retryCount + 1, resumeFromSaved);
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

    async function loadAndPlay(
      track: AudioItem,
      signal: AbortSignal,
      retryCount = 0,
      resumeFromSaved = false
    ): Promise<void> {
      if (!currentClient) throw new Error('Not authenticated');

      const audiobook = isAudiobook(track);
      const rate = audiobook ? resolveAudiobookSpeed(track.Id) : 1;

      // Seek-on-load resume is audiobook-only: a long-form book restores its saved place,
      // while music keeps its existing start-from-zero behaviour.
      if (resumeFromSaved && audiobook) {
        const resumeSeconds = resumePositionSeconds(track);
        pendingSeek = resumeSeconds > 0 ? resumeSeconds : null;
      } else {
        pendingSeek = null;
      }
      lastPauseAt = null;

      set({
        currentTrack: track,
        isLoading: true,
        error: null,
        isKaraokeMode: false,
        isKaraokeTransitioning: false,
        karaokeStatus: null,
        playerMode: audiobook ? 'audiobook' : 'music',
        playbackRate: rate,
      });
      reportStopped();

      try {
        const networkUrl = new ItemsService(currentClient).getStreamUrl(track.Id);
        const streamUrl = await useOfflineStore.getState().getPlaybackUrl(track.Id, networkUrl);

        await withTimeout(engine.load(streamUrl), ENGINE_TIMEOUT_MS);
        engine.setPlaybackRate?.(rate);
        set({ currentTrack: track, isLoading: false, error: null });

        if (signal.aborted) return;

        if (pendingSeek !== null) {
          engine.seek(pendingSeek);
          pendingSeek = null;
        }

        await engine.play();
        if (signal.aborted) return;

        commitPlaybackSideEffects(track, signal);
      } catch (error) {
        return handleLoadError(error, track, signal, retryCount, resumeFromSaved);
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
        suppressNextEnded = false;
        set({
          currentTrack: track,
          isLoading: false,
          error: null,
          isKaraokeTransitioning: false,
        });
        return;
      }

      // --- Commit: state matches engine reality ---
      set({ currentTrack: track, isPlaying: true, isLoading: false, error: null });
      consecutiveLoadFailures = 0;
      useTimingStore.getState().updateTiming(0, result.duration);

      // --- Side effects ---
      engine.setNormalizationGain?.(track.NormalizationGain ?? null);
      get().queue.advanceTo(track.Id);
      syncQueueState();
      updateSessionMetadata(track);
      syncMediaSessionPlayback('playing', 0);
      startPlaybackReporter(track.Id);
      void useOfflineStore.getState().cachePlayed(track);

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
      const audiobook = get().playerMode === 'audiobook';

      if (
        audiobook &&
        currentItemId &&
        now - lastLocalResumeWriteTime >= LOCAL_RESUME_WRITE_INTERVAL_MS
      ) {
        lastLocalResumeWriteTime = now;
        writeLocalResume(currentItemId, seconds, duration);
      }

      const reportInterval = audiobook
        ? AUDIOBOOK_PROGRESS_REPORT_INTERVAL_MS
        : PROGRESS_REPORT_INTERVAL_MS;
      if (playbackReporter && currentItemId && now - lastProgressReportTime >= reportInterval) {
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
    });

    engine.onEnded(() => {
      if (suppressNextEnded) {
        suppressNextEnded = false;
        return;
      }

      void controller.ifIdle(async signal => {
        const { repeatMode } = get();
        const next = resolveNextItem(get().queue, repeatMode);
        const action = endedAction(repeatMode, next);

        if (action.type === 'repeat-one') {
          engine.seek(0);
          await engine.play();
          return;
        }

        if (action.type === 'stop' || !next) {
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
          syncMediaSessionPlayback('none');
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

    if (typeof document !== 'undefined') {
      const recoverStalledAdvance = () => {
        const { currentTrack, isPlaying } = get();
        if (!currentTrack) return;

        const currentTime = engine.getCurrentTime();
        const duration = engine.getDuration();
        const ended = duration > 0 && currentTime >= duration - 0.5;
        const paused = !engine.isPlaying();

        if (isPlaying && paused && ended) {
          log.player.info('Recovering stalled track advance after background');
          void controller.interrupt(async signal => {
            const next = resolveNextItem(get().queue, get().repeatMode);
            if (!next) {
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
        }
      };

      document.addEventListener('visibilitychange', () => {
        if (document.visibilityState !== 'visible') {
          // Hidden is the last reliably observable state on mobile: the OS may discard a
          // backgrounded PWA without ever firing pagehide/unload. Flush the resume position here
          // (keepalive network + local write-through) so closing/backgrounding never loses the place.
          flushResumeOnHide();
          return;
        }
        recoverStalledAdvance();
      });

      // iOS suspends JS on a locked screen, so the 'ended' event for a track that finishes in the
      // background is deferred until the app/tab wakes. Reconcile on every wake signal so the next
      // track starts immediately on resume rather than needing a manual tap:
      //  - visibilitychange: tab hidden/shown (covered above)
      //  - focus: desktop alt-tab back (tab stayed visible)
      //  - pageshow: bfcache restore and installed-PWA resume from background
      if (globalThis.window !== undefined) {
        globalThis.window.addEventListener('focus', recoverStalledAdvance);
        globalThis.window.addEventListener('pageshow', recoverStalledAdvance);
        // pagehide is the desktop/standalone unload signal; it complements visibilitychange because
        // the two fire in different teardown paths across browsers (neither is 100% on mobile).
        globalThis.window.addEventListener('pagehide', flushResumeOnHide);
      }
    }

    // Stable controls registered once. next/previous are owned by updateMediaSessionNav() (called
    // from syncQueueState on every queue/track change) so they reflect actual availability.
    session?.setActionHandlers({
      onPlay: () => {
        get()
          .resume()
          .catch(() => {});
      },
      onPause: () => get().pause(),
      onSeek: seconds => get().seek(seconds),
    });

    async function enableKaraokeMode(currentTrack: AudioItem, signal: AbortSignal): Promise<void> {
      set({ karaokeEnabled: true, isKaraokeTransitioning: true });
      try {
        if (!currentClient) return;
        const status = await currentClient.getKaraokeStatus(currentTrack.Id);
        if (signal.aborted) return;

        if (status.state === 'NOT_STARTED') {
          if (karaokeFailedTrackIds.has(currentTrack.Id)) {
            set({ karaokeEnabled: false });
            return;
          }
          await currentClient.requestKaraokeProcessing(currentTrack.Id);
          set({ karaokeStatus: { state: 'PROCESSING', message: null } });
          return;
        }

        if (status.state === 'PROCESSING') {
          set({ karaokeStatus: status });
          return;
        }

        if (status.state === 'READY') {
          set({ isKaraokeMode: true, karaokeStatus: status });
          const instrumentalUrl = currentClient.getInstrumentalStreamUrl(currentTrack.Id);
          await karaokeSwitchUrl(instrumentalUrl, signal);
          if (!signal.aborted) {
            preloader.invalidate();
            schedulePreload();
          }
          return;
        }

        // FAILED
        karaokeFailedTrackIds.add(currentTrack.Id);
        set({ karaokeEnabled: false, karaokeStatus: status });
      } finally {
        set({ isKaraokeTransitioning: false });
      }
    }

    async function disableKaraokeMode(currentTrack: AudioItem, signal: AbortSignal): Promise<void> {
      if (!currentClient) return;
      set({ isKaraokeMode: false, isKaraokeTransitioning: true, karaokeEnabled: false });
      try {
        const networkUrl = new ItemsService(currentClient).getStreamUrl(currentTrack.Id);
        const streamUrl = await useOfflineStore
          .getState()
          .getPlaybackUrl(currentTrack.Id, networkUrl);
        await karaokeSwitchUrl(streamUrl, signal);
        if (!signal.aborted) {
          preloader.invalidate();
          schedulePreload();
          set({ karaokeStatus: null });
        }
      } finally {
        set({ isKaraokeTransitioning: false });
      }
    }

    return {
      ...initialState,

      playTrack: async track => {
        await controller.interrupt(async signal => {
          const { useSessionStore } = await import('./session-store');
          const sessionState = useSessionStore.getState();

          if (sessionState.activeSession) {
            const { queue, repeatMode } = get();
            const items = queue.getAllItems();
            const existingIndex = items.findIndex(item => item.Id === track.Id);

            if (existingIndex >= 0) {
              queue.advanceTo(track.Id);
            } else {
              const insertPos = queue.getCurrentIndex() + 1;
              queue.insertAt(track, insertPos);
              queue.advanceTo(track.Id);
            }
            queue.setRepeatMode(repeatMode);
            syncQueueState();
            await loadAndPlay(track, signal, 0, true);

            if (!signal.aborted) {
              sessionState
                .sendSignal({
                  signalType: 'QUEUE_JUMP',
                  trackId: track.Id,
                  context: {
                    positionPct: 0,
                    elapsedSec: 0,
                    autoplay: false,
                    selectedByUser: true,
                    timeOfDay: currentTimeOfDay(),
                  },
                })
                .catch(() => {});
            }
            return;
          }

          const { queue, repeatMode } = get();
          queue.setQueue([track], 0);
          queue.setRepeatMode(repeatMode);
          await loadAndPlay(track, signal, 0, true);
        });
      },

      playTracks: async (tracks, startIndex = 0) => {
        if (tracks.length === 0) return;

        await controller.interrupt(async signal => {
          const { useSessionStore } = await import('./session-store');
          const sessionState = useSessionStore.getState();
          if (sessionState.activeSession) {
            sessionState.endSession().catch(() => {});
          }

          const { queue, isShuffle, repeatMode } = get();
          queue.setQueue(tracks, startIndex);
          queue.setRepeatMode(repeatMode);
          if (isShuffle) {
            queue.setShuffleMode('on');
          }
          const currentTrack = queue.getCurrentItem();
          if (currentTrack) {
            await loadAndPlay(currentTrack, signal, 0, true);
          }
        });
      },

      playAlbum: async (albumId, startIndex = 0) => {
        await controller.interrupt(async signal => {
          if (!currentClient) throw new Error('Not authenticated');

          const { useSessionStore } = await import('./session-store');
          const sessionState = useSessionStore.getState();
          if (sessionState.activeSession) {
            sessionState.endSession().catch(() => {});
          }

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
            await loadAndPlay(currentTrack, signal, 0, true);
          }
        });
      },

      pause: () => {
        // eslint-disable-next-line @typescript-eslint/require-await
        void controller.interrupt(async () => {
          engine.pause();
          lastPauseAt = Date.now();
          set({ isPlaying: false });
          syncMediaSessionPlayback('paused');
          wakeLock.release();

          if (currentItemId) {
            writeLocalResumeNow();
            if (get().playerMode === 'audiobook') bumpResumeVersion();
            void useOfflineStore.getState().saveResume(currentItemId, engine.getCurrentTime());
          }

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
            if (get().playerMode === 'audiobook' && lastPauseAt !== null) {
              const rewindMs = getSmartRewindMs(Date.now() - lastPauseAt);
              if (rewindMs > 0) {
                const target = Math.max(0, engine.getCurrentTime() - rewindMs / 1000);
                engine.seek(target);
                useTimingStore.getState().seekTo(target, engine.getDuration());
              }
            }
            lastPauseAt = null;
            await engine.play();
            set({ isPlaying: true });
            syncMediaSessionPlayback('playing');
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
          const currentTime = useTimingStore.getState().currentTime;
          const { queue } = get();
          if (previousAction(currentTime, queue.hasPrevious()) === 'previous') {
            const prevTrack = queue.previous();
            if (prevTrack) {
              await loadAndPlay(prevTrack, signal);
            } else {
              engine.seek(0);
            }
          } else {
            engine.seek(0);
          }
        });
      },

      seek: seconds => {
        if (controller.isActive) {
          pendingSeek = seconds;
        } else {
          engine.seek(seconds);
        }
        useTimingStore.getState().seekTo(seconds, engine.getDuration());
        syncMediaSessionPlayback(get().isPlaying ? 'playing' : 'paused', seconds);

        if (get().playerMode === 'audiobook' && currentItemId) {
          writeLocalResume(currentItemId, seconds, engine.getDuration());
          bumpResumeVersion();
        }

        if (playbackReporter && currentItemId) {
          // 'Seek' tags this as an authoritative exact-set: a deliberate rewind must persist, not be
          // clamped forward by the server's furthest-position-wins heartbeat rule.
          playbackReporter
            .reportProgress(currentItemId, seconds, !get().isPlaying, 'Seek')
            .catch(err => {
              log.player.warn('Failed to report seek', { error: String(err) });
            });
        }
      },

      skipBy: deltaSeconds => {
        const duration = engine.getDuration();
        const raw = engine.getCurrentTime() + deltaSeconds;
        const target = duration > 0 ? Math.max(0, Math.min(raw, duration)) : Math.max(0, raw);
        get().seek(target);
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
          suppressNextEnded = false;
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
          set({ karaokeEnabled: !get().karaokeEnabled });
          return;
        }

        await controller.ifIdle(async signal => {
          try {
            if (isKaraokeMode) {
              await disableKaraokeMode(currentTrack, signal);
            } else {
              await enableKaraokeMode(currentTrack, signal);
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

        if (status.state === 'FAILED') {
          set({ karaokeEnabled: false, karaokeStatus: status, isKaraokeMode: false });
          return;
        }

        if (status.state !== 'READY') {
          set({ karaokeStatus: status });
          return;
        }

        set({ karaokeStatus: status });

        if (!get().karaokeEnabled) return;

        await controller.ifIdle(async signal => {
          const { currentTrack: ct2, karaokeStatus: ks2, karaokeEnabled: ke2 } = get();
          if (ct2?.Id !== currentTrack.Id || ks2?.state !== 'READY' || !ke2) return;

          set({ isKaraokeTransitioning: true, isKaraokeMode: true });
          try {
            if (!currentClient) return;
            const instrumentalUrl = currentClient.getInstrumentalStreamUrl(ct2.Id);
            await karaokeSwitchUrl(instrumentalUrl, signal);
            if (!signal.aborted) {
              preloader.invalidate();
              schedulePreload();
            }
          } finally {
            set({ isKaraokeTransitioning: false });
          }
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

      setPlaybackRate: (rate: number, scope: SpeedScope = 'book') => {
        const clamped = clampRate(rate);
        engine.setPlaybackRate?.(clamped);
        set({ playbackRate: clamped });
        persistAudiobookSpeed(clamped, scope, get().currentTrack?.Id ?? null);
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

      insertNextInQueue: (tracks: AudioItem[]) => {
        if (tracks.length === 0) return;
        const { queue, queueIndex } = get();
        for (let i = 0; i < tracks.length; i++) {
          const track = tracks[i];
          if (track) queue.insertAt(track, queueIndex + 1 + i);
        }
        syncQueueState();
        preloader.invalidate();
        schedulePreload();
      },

      removeFromQueue: (trackId: string) => {
        const { queue } = get();
        const items = queue.getAllItems();
        const index = items.findIndex(item => item.Id === trackId);
        if (index === -1 || index === queue.getCurrentIndex()) return;
        queue.removeAt(index);
        syncQueueState();
        preloader.invalidate();
        schedulePreload();
      },

      jumpToQueueTrack: async (trackId: string) => {
        await controller.interrupt(async signal => {
          const { queue } = get();
          const target = queue.getAllItems().find(item => item.Id === trackId);
          if (!target) return;
          queue.advanceTo(trackId);
          syncQueueState();
          try {
            await loadAndPlay(target, signal, 0, true);
          } catch {
            if (!signal.aborted) autoAdvanceOnError(get);
            return;
          }

          if (signal.aborted) return;

          const { useSessionStore } = await import('./session-store');
          const sessionState = useSessionStore.getState();
          if (sessionState.activeSession) {
            sessionState
              .sendSignal({
                signalType: 'QUEUE_JUMP',
                trackId,
                context: {
                  positionPct: 0,
                  elapsedSec: 0,
                  autoplay: false,
                  selectedByUser: true,
                  timeOfDay: currentTimeOfDay(),
                },
              })
              .catch(() => {});
          }
        });
      },

      // Single entry point the favorite mutation calls so player-owned state (the now-playing track
      // plus every cached queue copy) stays in lockstep with the React Query caches. The queue keeps
      // its own AudioItem copies, so a track favorited from a list must be patched here too or its
      // heart would be stale once playback advances to it.
      patchTrackFavorite: (itemId: string, isFavorite: boolean) => {
        const { currentTrack, queue } = get();
        const queueChanged = queue.setFavorite(itemId, isFavorite);
        const next: Partial<PlayerState> = {};
        if (
          currentTrack?.Id === itemId &&
          (currentTrack.UserData?.IsFavorite ?? false) !== isFavorite
        ) {
          next.currentTrack = setItemFavorite(currentTrack, isFavorite);
        }
        if (queueChanged) {
          next.queueItems = queue.getAllItems();
        }
        if (Object.keys(next).length > 0) set(next);
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

if (import.meta.hot) {
  import.meta.hot.dispose(() => {
    audioEngine?.dispose?.();
    audioEngine = null;
  });
}

export const useCurrentTrack = () => usePlayerStore(state => state.currentTrack);
export const useIsPlaying = () => usePlayerStore(state => state.isPlaying);
export const useIsLoading = () => usePlayerStore(state => state.isLoading);
export const useVolume = () => usePlayerStore(state => state.volume);
export const useIsShuffle = () => usePlayerStore(state => state.isShuffle);
export const useRepeatMode = () => usePlayerStore(state => state.repeatMode);
export const usePlayerError = () => usePlayerStore(state => state.error);
export const usePlayerMode = () => usePlayerStore(state => state.playerMode);
export const useIsAudiobookMode = () => usePlayerStore(state => state.playerMode === 'audiobook');
export const usePlaybackRate = () => usePlayerStore(state => state.playbackRate);
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
