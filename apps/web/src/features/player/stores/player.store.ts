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
  type MediaPlaybackError,
} from '@yay-tsa/platform';
import { reportError } from '@/shared/utils/error-reporter';
import { useAuthStore } from '@/features/auth/stores/auth.store';
import { useOfflineStore } from '@/features/offline/stores/offline.store';
import {
  writeLocalResume,
  readLocalResume,
  bumpResumeVersion,
} from '@/features/audiobooks/stores/local-resume';
import { log } from '@/shared/utils/logger';
import { toError } from '@/shared/utils/to-error';
import { queryClient } from '@/shared/lib/query-client';
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
  vocalBlend: number;
  sleepTimerMinutes: number | null;
  sleepTimerEndTime: number | null;
  playerMode: PlayerMode;
  playbackRate: number;
  normalizationEnabled: boolean;
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
  setVocalBlend: (level: number) => void;
  refreshKaraokeStatus: () => Promise<void>;
  setSleepTimer: (minutes: number | null) => void;
  clearSleepTimer: () => void;
  setPlaybackRate: (rate: number, scope?: SpeedScope) => void;
  updateCurrentTrackLyrics: (lyrics: string) => void;
  appendToQueue: (tracks: AudioItem[]) => void;
  insertNextInQueue: (tracks: AudioItem[]) => void;
  removeFromQueue: (trackId: string) => void;
  moveQueueItem: (fromIndex: number, toIndex: number) => void;
  jumpToQueueTrack: (trackId: string) => Promise<void>;
  retryCurrentTrack: () => Promise<void>;
  patchTrackFavorite: (itemId: string, isFavorite: boolean) => void;
  setNormalizationEnabled: (enabled: boolean) => void;
}

type PlayerStore = PlayerState & PlayerActions;

const VOLUME_STORAGE_KEY = 'yaytsa_volume';
const PROGRESS_REPORT_INTERVAL_MS = 10000;
// Audiobooks report to the server far more often than music: a lost minute of a 10-hour book is a
// real regression, whereas a song restarts cheaply. Local-first write-through is the primary
// durability layer; this tighter network heartbeat keeps cross-device resume fresh.
const AUDIOBOOK_PROGRESS_REPORT_INTERVAL_MS = 2000;
const LOCAL_RESUME_WRITE_INTERVAL_MS = 1000;
const RADIO_LOW_WATERMARK = 5;
const CROSSFADE_MS = 150;
const APPROACHING_END_MS = CROSSFADE_MS + 350;
const ENGINE_TIMEOUT_MS = 10000;
const MEDIA_ERR_NETWORK = 2;
const MEDIA_ERR_DECODE = 3;
const MEDIA_ERR_SRC_NOT_SUPPORTED = 4;
const NORMALIZATION_STORAGE_KEY = 'yaytsa_normalization_enabled';
const NORMALIZATION_MIN_DB = -18;
const NORMALIZATION_MAX_DB = 12;

export const UNSUPPORTED_FORMAT_MESSAGE = 'This track format is not supported on this device';

let audioEngine: AudioEngine | null = null;
let mediaSession: MediaSessionManager | null = null;
let playbackReporter: PlaybackReporter | null = null;
let currentItemId: string | null = null;
let currentClient: MediaServerClient | null = null;
let lastProgressReportTime = 0;
let lastLocalResumeWriteTime = 0;
// Instant the engine position was last live (tick, seek, or track load). Resume flushes are
// stamped with this so a tab that sat hidden for hours reports its position as of when it was
// true — letting the server merge and localStorage reject it against newer progress elsewhere.
let lastPositionTruthAt = 0;

const wakeLock = new WakeLockManager();

let sleepTimerId: ReturnType<typeof setTimeout> | null = null;
const karaokeFailedTrackIds = new Set<string>();

// A stem URL handed straight to the audio engine turns a 404 (stems not
// generated yet) into a scary MEDIA_ELEMENT_ERROR. Probe availability first
// so the normal "no stems yet" case stays a quiet, expected outcome.
async function isKaraokeStemAvailable(url: string, signal: AbortSignal): Promise<boolean> {
  const response = await fetch(url, {
    method: 'GET',
    headers: { Range: 'bytes=0-0' },
    credentials: 'same-origin',
    signal,
  });
  return response.ok;
}

function isRetryableTimeout(error: unknown, retryCount: number): boolean {
  return error instanceof Error && error.message.includes('Engine timeout') && retryCount < 1;
}

// ReplayGain rules: album gain when continuing an unshuffled same-album run (preserves
// intentional loudness differences between an album's tracks), track gain otherwise;
// whichever is missing falls back to the other. Values clamp to a safe window and
// convert dB → linear inside the engine (10^(dB/20) on the shared input gain bus).
function resolveNormalizationGainDb(track: AudioItem, isSameAlbumSequence: boolean): number | null {
  const preferredGainDb = isSameAlbumSequence
    ? (track.AlbumNormalizationGain ?? track.NormalizationGain)
    : (track.NormalizationGain ?? track.AlbumNormalizationGain);
  if (preferredGainDb == null || !Number.isFinite(preferredGainDb)) return null;
  return Math.max(NORMALIZATION_MIN_DB, Math.min(NORMALIZATION_MAX_DB, preferredGainDb));
}

// The gapless crossfade rides an approaching-end timer plus AudioContext/element-volume ramps, all
// of which browsers freeze or clamp once the page leaves the foreground (hidden tab, minimized, or a
// window that lost OS focus / got occluded). Starting it there can wedge the playback controller and
// starve the authoritative `ended` advance. Outside the foreground we skip the optimization so the
// media-clock `ended` event — which keeps firing regardless of focus — drives a plain hard-cut
// advance. visibilityState misses the unfocused-but-visible case, so hasFocus() is also required.
function isPlaybackForeground(): boolean {
  if (typeof document === 'undefined') return true;
  return document.visibilityState === 'visible' && document.hasFocus();
}

// The track item may carry stale UserData (React Query caches on album/search/queue surfaces),
// so the device's own localStorage write-through competes with it — except for finished
// chapters, whose ticks are deliberately zeroed so a re-listen starts clean.
function audiobookResumeSeconds(track: AudioItem): number {
  const local = readLocalResume(track.Id);
  const localSeconds = local && !track.UserData?.Played ? local.positionMs / 1000 : 0;
  return Math.max(resumePositionSeconds(track), localSeconds);
}

function getAudioEngine(): AudioEngine | null {
  if (audioEngine) return audioEngine;
  try {
    audioEngine = new HTML5AudioEngine({
      approachingEndThresholdMs: APPROACHING_END_MS,
      loadTimeoutMs: ENGINE_TIMEOUT_MS,
    });
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

function getSavedNormalizationEnabled(): boolean {
  try {
    return localStorage.getItem(NORMALIZATION_STORAGE_KEY) !== 'false';
  } catch {
    return true;
  }
}

function saveNormalizationEnabled(enabled: boolean): void {
  try {
    localStorage.setItem(NORMALIZATION_STORAGE_KEY, String(enabled));
  } catch {
    // Ignore storage errors
  }
}

let consecutiveLoadFailures = 0;
const MAX_CONSECUTIVE_FAILURES = 3;

// Playback-position reporting (start/progress/stopped) is fire-and-forget best-effort: a single
// failed report is a transient network blip, not a bug, so it logs at debug (never spams telemetry).
// But a SUSTAINED run of failures across these calls means the report pipeline itself is down (bad
// token, backend outage) and must surface — mirrors useDeviceHeartbeat's consecutive-failure escalator
// so this failure class isn't a permanent blind spot in client-error telemetry. Shared across all
// report call sites (start/progress/stopped) since they hit the same endpoint family and root cause.
let consecutivePlaybackReportFailures = 0;
function recordPlaybackReportOutcome(succeeded: boolean, context: string): void {
  if (succeeded) {
    consecutivePlaybackReportFailures = 0;
    return;
  }
  consecutivePlaybackReportFailures++;
  if (consecutivePlaybackReportFailures === MAX_CONSECUTIVE_FAILURES) {
    log.player.warn('Playback reporting failing repeatedly', {
      context,
      consecutiveFailures: consecutivePlaybackReportFailures,
    });
  }
}

function startPlaybackReporter(trackId: string): void {
  if (!currentClient) return;
  lastProgressReportTime = 0;
  playbackReporter = new PlaybackReporter(currentClient);
  playbackReporter
    .reportStart(trackId)
    .then(() => recordPlaybackReportOutcome(true, 'start'))
    .catch(err => {
      recordPlaybackReportOutcome(false, 'start');
      log.player.debug('Failed to report start', { error: String(err) });
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
  if (resolveNextItem(queue, repeatMode)) {
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
  vocalBlend: 0,
  sleepTimerMinutes: null,
  sleepTimerEndTime: null,
  playerMode: 'music',
  playbackRate: 1,
  normalizationEnabled: getSavedNormalizationEnabled(),
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
        setVocalBlend: () => {},
        refreshKaraokeStatus: async () => {},
        setSleepTimer: () => {},
        clearSleepTimer: () => {},
        setPlaybackRate: () => {},
        updateCurrentTrackLyrics: () => {},
        appendToQueue: () => {},
        insertNextInQueue: () => {},
        removeFromQueue: () => {},
        moveQueueItem: () => {},
        jumpToQueueTrack: async () => {},
        retryCurrentTrack: async () => {},
        patchTrackFavorite: () => {},
        setNormalizationEnabled: () => {},
      };
    }

    const engine = maybeEngine;
    engine.setVolume(initialState.volume);

    const controller = new PlaybackController();
    const preloader = new PreloadManager(engine);

    // A seek issued while a controller op is in flight; item-scoped so a seek aimed at
    // one track is never applied to the track that replaces it.
    let pendingSeek: { seconds: number; itemId: string | null } | null = null;
    let normalizationAlbumId: string | null = null;
    let currentNormalizationGainDb: number | null = null;
    let mediaErrorRecoveryUsedForItemId: string | null = null;
    // Wall-clock instant of the last pause, used to compute audiobook smart-rewind on resume.
    let lastPauseAt: number | null = null;

    // --- Helpers ---

    // Fails CLOSED, not open: the only call site's other staleness check (signal.aborted +
    // currentTrack mismatch, below) is not a full backstop on its own — an interrupting op can
    // do async setup (queue fetch, dynamic import) before it ever touches currentTrack, so a
    // superseded load's engine.load() can resolve inside that window with currentTrack still
    // unchanged and slip past it. When we can't verify which stream the engine holds, refusing
    // to claim state is safer than risking currentItemId/currentTrack getting clobbered by a
    // load that's no longer current.
    function engineHoldsStream(streamUrl: string): boolean {
      const element = engine.getAudioElement?.();
      if (!element) return false;
      try {
        return element.src === new URL(streamUrl, globalThis.location.href).href;
      } catch {
        return false;
      }
    }

    function syncQueueState(): void {
      const { queue } = get();
      set({ queueItems: queue.getAllItems(), queueIndex: queue.getCurrentIndex() });
      updateMediaSessionNav();
      maybeExtendRadioQueue();
    }

    // Endless radio: the backend has no playback position, so the client asks for more tracks as it
    // nears the end of its local queue. refreshQueue is debounced/in-flight-guarded in the session
    // store, so firing it on every queue sync is safe; we only import + call it once the unplayed
    // tail is actually short, keeping the hot path free of dynamic-import churn.
    function maybeExtendRadioQueue(): void {
      const { queueItems, queueIndex } = get();
      if (queueItems.length - (queueIndex + 1) > RADIO_LOW_WATERMARK) return;
      void import('./session-store').then(({ useSessionStore }) => {
        const session = useSessionStore.getState();
        if (session.activeSession?.isRadioMode) void session.refreshQueue();
      });
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
        playbackReporter
          .reportStopped(prevId, prevPos)
          .then(() => recordPlaybackReportOutcome(true, 'stopped'))
          .catch(err => {
            recordPlaybackReportOutcome(false, 'stopped');
            log.player.debug('Failed to report stopped', { error: String(err) });
          });
      }
    }

    // Write-through the current audiobook position to localStorage. Cheap, synchronous, and the
    // primary durability layer — independent of the network and the server cache.
    function writeLocalResumeNow(): void {
      if (get().playerMode !== 'audiobook' || !currentItemId) return;
      writeLocalResume(
        currentItemId,
        engine.getCurrentTime(),
        engine.getDuration(),
        lastPositionTruthAt
      );
    }

    // Best-effort durable flush for page unload / backgrounding: keepalive network write plus the
    // local write-through, so closing the tab mid-chapter never loses the place. A tab whose
    // position was never live (nothing played or sought) has nothing trustworthy to flush.
    function flushResumeOnHide(): void {
      if (!currentItemId || lastPositionTruthAt === 0) return;
      writeLocalResumeNow();
      playbackReporter?.flushProgress(
        currentItemId,
        engine.getCurrentTime(),
        !get().isPlaying,
        lastPositionTruthAt
      );
    }

    function updateSessionMetadata(track: AudioItem): void {
      if (!currentClient) return;
      // The image endpoint resolves covers by id (ignoring the tag), so advertise the cover by
      // album id (falling back to the track id) whenever the track has any Primary cover. The tag
      // is only a cache hint; a coverless track 404s and the OS simply shows no artwork.
      let imageUrl: string | undefined;
      const coverTag = track.ImageTags?.Primary ?? track.AlbumPrimaryImageTag;
      if (coverTag) {
        imageUrl = currentClient.getImageUrl(track.AlbumId ?? track.Id, 'Primary', {
          tag: coverTag,
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
        preloader.prepare(next.Id, new ItemsService(currentClient).getStreamUrl(next.Id));
      } else {
        preloader.invalidate();
      }
    }

    function markKaraokeUnavailable(reason: string): void {
      log.player.debug('Karaoke stem unavailable, skipping preload', { reason });
      set({ isKaraokeMode: false, isKaraokeTransitioning: false, karaokeStatus: null });
    }

    async function applyReadyKaraokeState(
      trackId: string,
      status: KaraokeStatus,
      signal: AbortSignal
    ): Promise<void> {
      set({ isKaraokeTransitioning: true });
      try {
        if (!currentClient) return;
        const instrumentalUrl = currentClient.getInstrumentalStreamUrl(trackId);
        if (!(await isKaraokeStemAvailable(instrumentalUrl, signal))) {
          if (!signal.aborted) markKaraokeUnavailable('stem not ready');
          return;
        }
        if (signal.aborted) return;
        await enterVocalBlend(trackId, signal);
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

    // Co-play instrumental + vocals stems for the live vocal-blend slider. The
    // instrumental drives position/seek; the vocals stem is mixed in at the current
    // blend gain. Online-only: stems stream from the backend Range endpoints.
    async function enterVocalBlend(trackId: string, signal: AbortSignal): Promise<void> {
      if (!currentClient || !engine.enterVocalBlend) return;
      const position = engine.getCurrentTime();
      const wasPlaying = get().isPlaying;
      const instrumentalUrl = currentClient.getInstrumentalStreamUrl(trackId);
      const vocalsUrl = currentClient.getVocalStreamUrl(trackId);
      engine.setVocalBlend?.(get().vocalBlend);
      await withTimeout(
        engine.enterVocalBlend(instrumentalUrl, vocalsUrl, position, wasPlaying),
        ENGINE_TIMEOUT_MS
      );
      if (signal.aborted) return;
      useTimingStore.getState().updateTiming(engine.getCurrentTime(), engine.getDuration());

      const seekTarget = pendingSeek;
      if (seekTarget && seekTarget.itemId === get().currentTrack?.Id) {
        engine.seek(seekTarget.seconds);
        useTimingStore.getState().seekTo(seekTarget.seconds, engine.getDuration());
        pendingSeek = null;
      }
    }

    async function exitVocalBlend(resumeUrl: string, signal: AbortSignal): Promise<void> {
      if (!engine.exitVocalBlend) {
        await withTimeout(engine.load(resumeUrl), ENGINE_TIMEOUT_MS);
        if (!signal.aborted && get().isPlaying) await engine.play();
        return;
      }
      const position = engine.getCurrentTime();
      const wasPlaying = get().isPlaying;
      await withTimeout(engine.exitVocalBlend(resumeUrl, position, wasPlaying), ENGINE_TIMEOUT_MS);
      if (signal.aborted) return;
      useTimingStore.getState().updateTiming(engine.getCurrentTime(), engine.getDuration());
    }

    function applyNormalizationGain(track: AudioItem): void {
      const isSameAlbumSequence =
        !get().isShuffle && track.AlbumId != null && track.AlbumId === normalizationAlbumId;
      normalizationAlbumId = track.AlbumId ?? null;
      currentNormalizationGainDb = resolveNormalizationGainDb(track, isSameAlbumSequence);
      engine.setNormalizationGain?.(get().normalizationEnabled ? currentNormalizationGainDb : null);
    }

    // --- Core playback commands ---

    function commitPlaybackSideEffects(track: AudioItem, signal: AbortSignal): void {
      currentItemId = track.Id;
      set({ isPlaying: true });
      consecutiveLoadFailures = 0;
      wakeLock.acquire().catch(() => {});

      applyNormalizationGain(track);
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
        error: toError(error),
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
      // while music keeps its existing start-from-zero behaviour. A user seek already
      // aimed at this track survives; seeks aimed at other tracks are discarded.
      if (resumeFromSaved && audiobook) {
        const resumeSeconds = audiobookResumeSeconds(track);
        if (resumeSeconds > 0) {
          pendingSeek = { seconds: resumeSeconds, itemId: track.Id };
        } else if (pendingSeek?.itemId !== track.Id) {
          pendingSeek = null;
        }
      } else if (pendingSeek?.itemId !== track.Id) {
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
        // The engine is always handed the same-origin stream URL — never a blob: URL, which a
        // strict `media-src 'self'` CSP rejects ("Media load rejected by URL safety check").
        // When the track is downloaded, audio-offline-sw.js intercepts this exact URL and serves
        // the bytes from IndexedDB (Range-aware), so offline playback needs no blob: exception.
        const streamUrl = new ItemsService(currentClient).getStreamUrl(track.Id);

        await withTimeout(engine.load(streamUrl), ENGINE_TIMEOUT_MS);

        // A superseded load resolves silently while the engine already holds the next
        // track; only the op whose stream the engine actually holds may claim state.
        if (!engineHoldsStream(streamUrl)) return;
        if (signal.aborted && get().currentTrack?.Id !== track.Id) return;

        // Engine truth: from here on, positions read from the engine belong to this item.
        currentItemId = track.Id;
        lastPositionTruthAt = Date.now();
        engine.setPlaybackRate?.(rate);
        set({ currentTrack: track, isLoading: false, error: null });

        if (signal.aborted) return;

        if (pendingSeek?.itemId === track.Id) {
          engine.seek(pendingSeek.seconds);
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
      currentItemId = track.Id;
      lastPositionTruthAt = Date.now();
      // The element swap starts the new element at the browser-default rate; an
      // audiobook chapter advance must keep the user-selected speed audible.
      engine.setPlaybackRate?.(get().playbackRate);

      if (signal.aborted) {
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
      applyNormalizationGain(track);
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

      if (pendingSeek?.itemId === track.Id) {
        engine.seek(pendingSeek.seconds);
        useTimingStore.getState().seekTo(pendingSeek.seconds, engine.getDuration());
        pendingSeek = null;
      }
    }

    // --- Engine event handlers ---

    engine.onTimeUpdate(seconds => {
      // While a user seek is pending against the current track (issued mid-transition), the
      // optimistic position owns the UI: engine ticks still reflect the pre-seek position and
      // would visibly snap the bar back until the seek is applied after the transition.
      if (pendingSeek && pendingSeek.itemId === get().currentTrack?.Id) return;
      const duration = engine.getDuration();
      useTimingStore.getState().updateTiming(seconds, duration);

      const now = Date.now();
      lastPositionTruthAt = now;
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
        playbackReporter
          .reportProgress(currentItemId, seconds, false)
          .then(() => recordPlaybackReportOutcome(true, 'progress'))
          .catch(err => {
            recordPlaybackReportOutcome(false, 'progress');
            log.player.debug('Failed to report playback progress', { error: String(err) });
          });
      }
    });

    engine.onApproachingEnd?.(() => {
      // Background/unfocused tabs throttle the timers and freeze the AudioContext ramps this
      // crossfade depends on, so skip it and let the `ended` handler advance instead — otherwise a
      // half-run transition wedges the controller and playback stalls until the window regains focus.
      if (!isPlaybackForeground()) return;
      void controller.ifIdle(async signal => {
        const { queue, repeatMode } = get();
        if (repeatMode === 'one') return;

        const next = resolveNextItem(queue, repeatMode);
        if (!next || !preloader.isReady(next.Id)) return;

        await gaplessTransition(next, false, signal);
      });
    });

    engine.onEnded(() => {
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
            playbackReporter
              .reportStopped(currentItemId, engine.getCurrentTime())
              .then(() => recordPlaybackReportOutcome(true, 'natural-end'))
              .catch(err => {
                recordPlaybackReportOutcome(false, 'natural-end');
                log.player.debug('Failed to report stop on natural end', { error: String(err) });
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
      log.player.debug('Audio engine error', { error: String(error) });

      const mediaErrorCode = (error as Partial<MediaPlaybackError>).mediaErrorCode ?? null;
      const audioEl = engine.getAudioElement?.() ?? null;
      reportError(error, 'audio', {
        type: 'AudioError',
        audio: {
          mediaError: mediaErrorCode,
          readyState: audioEl?.readyState,
          networkState: audioEl?.networkState,
        },
      });
      const track = get().currentTrack;

      // An unsupported codec can never be recovered by reloading the same source, so don't stall:
      // surface a format-specific error and skip to the next track using the shared advance path.
      if (mediaErrorCode === MEDIA_ERR_SRC_NOT_SUPPORTED) {
        const unsupportedError: MediaPlaybackError = Object.assign(
          new Error(UNSUPPORTED_FORMAT_MESSAGE),
          { mediaErrorCode }
        );
        set({ error: unsupportedError, isPlaying: false, isLoading: false });
        autoAdvanceOnError(get);
        return;
      }

      const recoverable =
        (mediaErrorCode === MEDIA_ERR_NETWORK || mediaErrorCode === MEDIA_ERR_DECODE) &&
        track !== null &&
        currentItemId === track.Id &&
        mediaErrorRecoveryUsedForItemId !== track.Id &&
        !controller.isActive &&
        currentClient !== null;

      if (!recoverable) {
        set({ error, isPlaying: false, isLoading: false });
        return;
      }

      mediaErrorRecoveryUsedForItemId = track.Id;
      const wasPlaying = get().isPlaying;
      const lastKnownPosition = useTimingStore.getState().currentTime;
      log.player.warn('Attempting one-shot media error recovery', {
        trackId: track.Id,
        mediaErrorCode,
        position: lastKnownPosition,
      });

      void controller.interrupt(async signal => {
        try {
          if (!currentClient) throw new Error('Not authenticated');
          if (get().isKaraokeMode) {
            await enterVocalBlend(track.Id, signal);
            if (signal.aborted) return;
            engine.setPlaybackRate?.(get().playbackRate);
            if (lastKnownPosition > 0) engine.seek(lastKnownPosition);
            set({ error: null, isPlaying: wasPlaying, isLoading: false });
            return;
          }
          const streamUrl = new ItemsService(currentClient).getStreamUrl(track.Id);
          await withTimeout(engine.load(streamUrl), ENGINE_TIMEOUT_MS);
          if (signal.aborted) return;
          engine.setPlaybackRate?.(get().playbackRate);
          if (lastKnownPosition > 0) engine.seek(lastKnownPosition);
          if (wasPlaying) await engine.play();
          if (signal.aborted) return;
          set({ error: null, isPlaying: wasPlaying, isLoading: false });
        } catch (recoveryError) {
          if (signal.aborted) return;
          log.player.error('Media error recovery failed', recoveryError);
          set({ error, isPlaying: false, isLoading: false });
        }
      });
    });

    if (typeof document !== 'undefined') {
      const recoverStalledAdvance = () => {
        // A cooperative advance (onEnded) or crossfade may already be in flight — it moves the
        // queue index synchronously before awaiting the load. Preempting it here would re-read the
        // already-advanced index and advance a second time, skipping every other chapter. Only
        // recover when nothing is running (i.e. iOS deferred 'ended' never fired).
        if (controller.isActive) return;
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
          const instrumentalUrl = currentClient.getInstrumentalStreamUrl(currentTrack.Id);
          if (!(await isKaraokeStemAvailable(instrumentalUrl, signal))) {
            if (!signal.aborted) {
              set({ isKaraokeMode: false, karaokeEnabled: false, karaokeStatus: null });
              log.player.debug('Karaoke stem unavailable on enable, skipping preload', {
                trackId: currentTrack.Id,
              });
            }
            return;
          }
          if (signal.aborted) return;
          set({ isKaraokeMode: true, karaokeStatus: status });
          await enterVocalBlend(currentTrack.Id, signal);
          if (!signal.aborted) {
            preloader.invalidate();
            schedulePreload();
          }
          return;
        }

        // FAILED: an explicit user toggle gets one re-processing attempt per session —
        // the backend resets the fail counter (requeueFailed), so a track that failed
        // during a past separator outage self-heals instead of staying dead forever.
        if (!karaokeFailedTrackIds.has(currentTrack.Id)) {
          karaokeFailedTrackIds.add(currentTrack.Id);
          await currentClient.requestKaraokeProcessing(currentTrack.Id);
          set({ karaokeStatus: { state: 'PROCESSING', message: null } });
          return;
        }
        set({ karaokeEnabled: false, karaokeStatus: status });
      } finally {
        set({ isKaraokeTransitioning: false });
      }
    }

    async function disableKaraokeMode(currentTrack: AudioItem, signal: AbortSignal): Promise<void> {
      if (!currentClient) return;
      set({
        isKaraokeMode: false,
        isKaraokeTransitioning: true,
        karaokeEnabled: false,
      });
      try {
        const streamUrl = new ItemsService(currentClient).getStreamUrl(currentTrack.Id);
        await exitVocalBlend(streamUrl, signal);
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
          lastPositionTruthAt = lastPauseAt;
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
              .then(() => recordPlaybackReportOutcome(true, 'pause'))
              .catch(err => {
                recordPlaybackReportOutcome(false, 'pause');
                log.player.debug('Failed to report pause', { error: String(err) });
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
                .then(() => recordPlaybackReportOutcome(true, 'resume'))
                .catch(err => {
                  recordPlaybackReportOutcome(false, 'resume');
                  log.player.debug('Failed to report resume', { error: String(err) });
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
          const { queue } = get();
          const next = queue.next({ manual: true });
          if (!next) {
            engine.pause();
            wakeLock.release();
            if (playbackReporter && currentItemId) {
              playbackReporter
                .reportStopped(currentItemId, engine.getCurrentTime())
                .then(() => recordPlaybackReportOutcome(true, 'queue-end'))
                .catch(err => {
                  recordPlaybackReportOutcome(false, 'queue-end');
                  log.player.debug('Failed to report stop on queue end', { error: String(err) });
                });
              playbackReporter = null;
              currentItemId = null;
            }
            useTimingStore.getState().reset();
            set({ isPlaying: false });
            return;
          }
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
            const prevTrack = queue.previous({ manual: true });
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
          pendingSeek = { seconds, itemId: get().currentTrack?.Id ?? null };
        } else {
          engine.seek(seconds);
        }
        lastPositionTruthAt = Date.now();
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
            .then(() => recordPlaybackReportOutcome(true, 'seek'))
            .catch(err => {
              recordPlaybackReportOutcome(false, 'seek');
              log.player.debug('Failed to report seek', { error: String(err) });
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

      setNormalizationEnabled: enabled => {
        saveNormalizationEnabled(enabled);
        set({ normalizationEnabled: enabled });
        engine.setNormalizationGain?.(enabled ? currentNormalizationGainDb : null);
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
          pendingSeek = null;
          // Capture the real position before the engine resets to 0: a stopped report is an
          // authoritative exact-set on the server, so reporting 0 here would wipe the resume
          // point of a mid-chapter audiobook (stop fires on logout/auth-clear, not user intent).
          const stoppedPosition = engine.getCurrentTime();
          writeLocalResumeNow();
          engine.pause();
          engine.seek(0);
          useTimingStore.getState().reset();
          preloader.invalidate();
          wakeLock.release();

          if (playbackReporter && currentItemId) {
            playbackReporter
              .reportStopped(currentItemId, stoppedPosition, lastPositionTruthAt || undefined)
              .then(() => recordPlaybackReportOutcome(true, 'stop'))
              .catch(err => {
                recordPlaybackReportOutcome(false, 'stop');
                log.player.debug('Failed to report stop', { error: String(err) });
              });
            playbackReporter = null;
            currentItemId = null;
          }

          if (sleepTimerId) {
            clearTimeout(sleepTimerId);
            sleepTimerId = null;
          }

          const { queue: currentQueue, playerMode } = get();
          currentQueue.setQueue([], 0);

          // Audiobook resume ticks are written server-side on stop; drop the cached audiobooks
          // shelf so reopening within staleTime re-reads the fresh resume point, not a stale tick.
          if (playerMode === 'audiobook') {
            void queryClient.invalidateQueries({ queryKey: ['audiobooks'] });
          }

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
            const isAbort =
              (error instanceof DOMException && error.name === 'AbortError') ||
              (error instanceof Error && error.message.includes('Preload superseded'));
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

      setVocalBlend: (level: number) => {
        const clamped = Math.max(0, Math.min(1, level));
        set({ vocalBlend: clamped });
        engine.setVocalBlend?.(clamped);
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
            if (!(await isKaraokeStemAvailable(instrumentalUrl, signal))) {
              if (!signal.aborted) markKaraokeUnavailable('stem not ready on refresh');
              return;
            }
            if (signal.aborted) return;
            await enterVocalBlend(ct2.Id, signal);
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
        const { queue } = get();
        // The queue object is the source of truth for the current index: the mirrored
        // queueIndex state lags until playback side effects commit, so a "play next"
        // issued while a track is still loading would otherwise insert at the front.
        const insertBase = queue.getCurrentIndex();
        for (let i = 0; i < tracks.length; i++) {
          const track = tracks[i];
          if (track) queue.insertAt(track, insertBase + 1 + i);
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

      // Pure queue mutation: the engine is never touched, so the playing track keeps
      // playing and PlaybackQueue.moveItem remaps currentIndex to follow it.
      moveQueueItem: (fromIndex: number, toIndex: number) => {
        const { queue } = get();
        if (!queue.moveItem(fromIndex, toIndex)) return;
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

      retryCurrentTrack: async () => {
        const track = get().currentTrack;
        if (!track) return;
        const lastKnownPosition = useTimingStore.getState().currentTime;
        mediaErrorRecoveryUsedForItemId = null;
        await controller.interrupt(async signal => {
          if (lastKnownPosition > 0) {
            pendingSeek = { seconds: lastKnownPosition, itemId: track.Id };
          }
          try {
            await loadAndPlay(track, signal);
          } catch {
            // Error state already set by loadAndPlay; the user can retry again.
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

export function getPlayerEngineForSync(): AudioEngine | null {
  return audioEngine;
}

useAuthStore.subscribe(
  state => state.client,
  client => {
    currentClient = client;

    if (!client) {
      usePlayerStore.getState().stop();
    }
  }
);

// Lock-screen ±15/30s skip controls exist only in audiobook mode; in music mode they stay
// unregistered so they never crowd out next/previous track on iOS.
usePlayerStore.subscribe(
  state => state.playerMode,
  playerMode => {
    const session = getMediaSession();
    if (!session) return;
    try {
      if (playerMode === 'audiobook') {
        session.setSkipHandlers(
          () => usePlayerStore.getState().skipBy(-15),
          () => usePlayerStore.getState().skipBy(30)
        );
      } else {
        session.setSkipHandlers(null, null);
      }
    } catch (e) {
      log.player.warn('Failed to update media session skip handlers', { error: String(e) });
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
export const useIsKaraokeMode = () => usePlayerStore(state => state.isKaraokeMode);
export const useIsKaraokeTransitioning = () =>
  usePlayerStore(state => state.isKaraokeTransitioning);
export const useKaraokeEnabled = () => usePlayerStore(state => state.karaokeEnabled);
export const useKaraokeStatus = () => usePlayerStore(state => state.karaokeStatus);
export const useVocalBlend = () => usePlayerStore(state => state.vocalBlend);
export const useSleepTimer = () =>
  usePlayerStore(
    useShallow(state => ({
      minutes: state.sleepTimerMinutes,
      endTime: state.sleepTimerEndTime,
    }))
  );
export const useQueueItems = () => usePlayerStore(state => state.queueItems);
export const useQueueIndex = () => usePlayerStore(state => state.queueIndex);
