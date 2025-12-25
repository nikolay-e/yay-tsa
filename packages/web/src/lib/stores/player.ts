/**
 * Player store
 * Manages playback state, queue, and audio engine
 */

import { writable, derived, get } from 'svelte/store';
import { browser } from '$app/environment';
import {
  PlaybackQueue,
  PlaybackState,
  createLogger,
  type AudioItem,
  type RepeatMode,
} from '@yaytsa/core';
import { HTML5AudioEngine, MediaSessionManager, type AudioEngine } from '@yaytsa/platform';
import { client } from './auth.js';
import { karaoke, shouldUseInstrumental } from './karaoke.js';

const log = createLogger('Player');

const RESTART_THRESHOLD_SECONDS = 3;
const RETINA_IMAGE_SIZE = 1024;
const VOLUME_STORAGE_KEY = 'yaytsa_volume';
const DEFAULT_VOLUME = 0.7;

interface PlayerState {
  queue: PlaybackQueue;
  state: PlaybackState | null;
  audioEngine: AudioEngine | null;
  currentTrack: AudioItem | null;
  volume: number;
  isPlaying: boolean;
  isLoading: boolean;
  isShuffle: boolean;
  repeatMode: RepeatMode;
  error: Error | null;
}

interface PlayerTimingState {
  currentTime: number;
  duration: number;
  buffered: number;
}

// Initialize core components
const queue = new PlaybackQueue();
// Audio engine and media session are only available in browser
let audioEngine: HTML5AudioEngine | null = null;
let mediaSession: MediaSessionManager | null = null;

// Race condition prevention
let currentLoadOperation: symbol | null = null; // Unique ID for track loading operations
let isAutoAdvancing = false; // Prevents duplicate onEnded events

// Cleanup functions for subscriptions (memory leak prevention)
let clientUnsubscribe: (() => void) | null = null;
let audioEventCleanups: Array<() => void> = [];

// Performance optimization: Cache duration and throttle updates
let cachedDuration = 0; // Cache track duration to avoid repeated getDuration() calls
let lastMediaSessionUpdate = 0; // Timestamp of last MediaSession update
const MEDIA_SESSION_UPDATE_INTERVAL_MS = 1000; // 1 time/sec for lock screen

// Separate high-frequency timing store (RAF-throttled for performance)
const playerTimingStore = writable<PlayerTimingState>({
  currentTime: 0,
  duration: 0,
  buffered: 0,
});

let rafId: number | null = null;
let pendingTimingUpdate: PlayerTimingState | null = null;

function loadPersistedVolume(): number {
  if (!browser) return DEFAULT_VOLUME;
  try {
    const stored = localStorage.getItem(VOLUME_STORAGE_KEY);
    if (stored) {
      const volume = parseFloat(stored);
      if (!isNaN(volume) && volume >= 0 && volume <= 1) {
        return volume;
      }
    }
  } catch {
    // localStorage unavailable or corrupted
  }
  return DEFAULT_VOLUME;
}

function persistVolume(volume: number): void {
  if (!browser) return;
  try {
    localStorage.setItem(VOLUME_STORAGE_KEY, volume.toString());
  } catch {
    // localStorage unavailable
  }
}

function updateTiming(time: number, duration: number) {
  pendingTimingUpdate = {
    currentTime: time,
    duration,
    buffered: 0,
  };

  if (rafId === null) {
    rafId = requestAnimationFrame(() => {
      if (pendingTimingUpdate) {
        playerTimingStore.set(pendingTimingUpdate);
        pendingTimingUpdate = null;
      }
      rafId = null;
    });
  }
}

// Initialize audio engine and media session in browser only
if (browser) {
  audioEngine = new HTML5AudioEngine();
  mediaSession = new MediaSessionManager();

  // Initialize karaoke store with audio engine reference
  karaoke.setAudioEngine(audioEngine);

  // Log media session support (development only)
  if (mediaSession.supported()) {
    log.info('[Media Session] Supported - background playback enabled');
  } else {
    log.info('[Media Session] Not supported - background playback limited');
  }
}

// Initialize PlaybackState when client is available
let playbackState: PlaybackState | null = null;

const persistedVolume = loadPersistedVolume();

const initialState: PlayerState = {
  queue,
  state: null,
  audioEngine,
  currentTrack: null,
  volume: persistedVolume,
  isPlaying: false,
  isLoading: false,
  isShuffle: false,
  repeatMode: 'off',
  error: null,
};

const playerStore = writable<PlayerState>(initialState);

// Subscribe to client changes AFTER playerStore is created
// Store unsubscribe function for cleanup (memory leak prevention)
clientUnsubscribe = client.subscribe($client => {
  if ($client) {
    playbackState = new PlaybackState($client);
    // Keep playback reporting in sync with current UI volume
    const currentVolume = get(playerStore).volume;
    playbackState.setVolume(currentVolume);
    playerStore.update(state => ({ ...state, state: playbackState }));
  } else {
    // Client disconnected (logout) - clear playback but preserve volume
    if (playbackState) {
      playbackState.dispose();
    }
    playbackState = null;
    const currentVolume = get(playerStore).volume;
    playerStore.set({ ...initialState, volume: currentVolume });
  }
});

// Subscribe to karaoke mode changes for live stream switching
let previousUseInstrumental = false;
let karaokeUnsubscribe: (() => void) | null = null;
if (browser) {
  karaokeUnsubscribe = shouldUseInstrumental.subscribe($shouldUse => {
    // Only react to actual changes (skip initial subscription call)
    if ($shouldUse !== previousUseInstrumental) {
      previousUseInstrumental = $shouldUse;

      // If track is playing, reload with new stream URL
      const state = get(playerStore);
      const $client = get(client);
      if (state.currentTrack && $client && audioEngine) {
        const wasPlaying = state.isPlaying;
        const currentPosition = audioEngine.getCurrentTime();
        // Capture track ID for race condition protection
        const targetTrackId = state.currentTrack.Id;

        // Compute URLs: old uses !$shouldUse (opposite of new state)
        const oldStreamUrl = !$shouldUse
          ? $client.getInstrumentalStreamUrl(targetTrackId)
          : $client.getStreamUrl(targetTrackId);
        const newStreamUrl = $shouldUse
          ? $client.getInstrumentalStreamUrl(targetTrackId)
          : $client.getStreamUrl(targetTrackId);

        log.info('Karaoke mode changed, switching stream', {
          trackId: targetTrackId,
          useInstrumental: $shouldUse,
        });

        // Set loading state during stream swap
        playerStore.update(s => ({ ...s, isLoading: true }));

        // Reload track with new URL, restore position
        audioEngine
          .load(newStreamUrl)
          .then(async () => {
            // Verify track hasn't changed during async load
            const currentState = get(playerStore);
            if (currentState.currentTrack?.Id !== targetTrackId) {
              log.debug('Track changed during karaoke stream swap, aborting');
              return;
            }

            // Only seek after metadata is loaded (getDuration > 0 means seekable)
            const duration = audioEngine!.getDuration();
            if (duration > 0 && currentPosition < duration) {
              audioEngine!.seek(currentPosition);
            }
            if (wasPlaying) {
              return audioEngine!.play();
            }
          })
          .then(() => {
            // Verify track still matches before updating state
            const currentState = get(playerStore);
            if (currentState.currentTrack?.Id === targetTrackId) {
              playerStore.update(s => ({ ...s, isLoading: false, error: null }));
            }
          })
          .catch(err => {
            log.error('Failed to switch karaoke stream', err);
            // Only update error state if track still matches
            const currentState = get(playerStore);
            if (currentState.currentTrack?.Id === targetTrackId) {
              playerStore.update(s => ({
                ...s,
                isLoading: false,
                error: err instanceof Error ? err : new Error('Failed to switch stream'),
              }));
            }
            // Try to restore previous stream
            audioEngine!.load(oldStreamUrl).catch(() => {
              log.error('Failed to restore previous stream');
            });
          });
      }
    }
  });
}

if (audioEngine) {
  audioEngine.setVolume(persistedVolume);

  // Time update handler (RAF-throttled for smooth performance)
  // Store cleanup function for memory leak prevention
  audioEventCleanups.push(
    audioEngine.onTimeUpdate(time => {
      // Always update PlaybackState time (needed for accurate server reporting every 10s)
      if (playbackState) {
        playbackState.setCurrentTime(time);
      }

      // RAF-throttled UI updates (max 60fps, but typically 10-30fps depending on browser)
      updateTiming(time, cachedDuration);

      // Debounce MediaSession updates for lock screen seek bar (1 time/sec)
      const now = Date.now();
      if (
        mediaSession &&
        cachedDuration > 0 &&
        now - lastMediaSessionUpdate >= MEDIA_SESSION_UPDATE_INTERVAL_MS
      ) {
        lastMediaSessionUpdate = now;
        mediaSession.updatePositionState(cachedDuration, time);
      }
    })
  );

  // Track ended handler - auto advance to next
  // Store cleanup function for memory leak prevention
  audioEventCleanups.push(
    audioEngine.onEnded(() => {
      // Prevent duplicate onEnded events (browser bug protection)
      if (isAutoAdvancing) return;
      isAutoAdvancing = true;

      const state = get(playerStore);
      const timing = get(playerTimingStore);

      // Report completion
      if (state.state && state.currentTrack) {
        state.state.setStatus('stopped');
        state.state.setCurrentTime(timing.currentTime);
        void state.state.reportPlaybackStop();
      }

      // Auto-advance to next track
      next()
        .catch(error => {
          log.error('Auto-advance error:', error);
          playerStore.update(s => ({
            ...s,
            isPlaying: false,
            error: error instanceof Error ? error : new Error(String(error)),
          }));
        })
        .finally(() => {
          isAutoAdvancing = false;
        });
    })
  );

  // Error handler
  // Store cleanup function for memory leak prevention
  audioEventCleanups.push(
    audioEngine.onError(error => {
      log.error('Audio playback error:', error);
      playerStore.update(state => ({
        ...state,
        isPlaying: false,
        isLoading: false,
        error,
      }));
    })
  );

  // Loading state handler
  // Store cleanup function for memory leak prevention
  audioEventCleanups.push(
    audioEngine.onLoading(isLoading => {
      playerStore.update(state => ({
        ...state,
        isLoading,
      }));
    })
  );
}

/**
 * Play a single track and set queue to just this track
 */
async function play(track: AudioItem): Promise<void> {
  const state = get(playerStore);
  state.queue.setQueue([track]);
  await playTrackFromQueue(track);
}

/**
 * Play a track from existing queue (does not modify queue)
 */
async function playTrackFromQueue(track: AudioItem): Promise<void> {
  // Create unique operation ID for cancellation
  const operationId = Symbol('load-operation');
  currentLoadOperation = operationId;

  const state = get(playerStore);
  const $client = get(client);

  if (!$client) {
    throw new Error('Not authenticated');
  }

  if (!audioEngine) {
    throw new Error('Audio engine not available');
  }

  try {
    // Notify karaoke store about track change (triggers status check)
    void karaoke.setCurrentTrack(track.Id);

    // Get stream URL - use instrumental if server-side karaoke is active and ready
    const useInstrumental = get(shouldUseInstrumental);
    const streamUrl = useInstrumental
      ? $client.getInstrumentalStreamUrl(track.Id)
      : $client.getStreamUrl(track.Id);

    log.info('Loading track', { trackId: track.Id, useInstrumental });

    // Load and play
    playerStore.update(s => ({ ...s, isLoading: true, error: null, currentTrack: track }));

    await audioEngine.load(streamUrl);

    // Cache duration after load to avoid repeated getDuration() calls in hot path
    cachedDuration = audioEngine.getDuration();

    // Synchronize volume with store (critical after sleep timer or other volume changes)
    const currentVolume = get(playerStore).volume;
    audioEngine.setVolume(currentVolume);

    // Check if operation was cancelled during load
    if (currentLoadOperation !== operationId) {
      return; // Operation cancelled, exit silently
    }

    await audioEngine.play();

    // Check again after play (in case of very fast track switching)
    if (currentLoadOperation !== operationId) {
      audioEngine.pause();
      return; // Operation cancelled, stop playback
    }

    // Report playback start (non-blocking - don't wait for server response)
    if (state.state) {
      state.state.setCurrentItem(track);
      state.state.setStatus('playing');
      void state.state.reportPlaybackStart(); // Fire-and-forget to avoid UI lag
    }

    playerStore.update(s => ({
      ...s,
      isPlaying: true,
      isLoading: false,
      currentTrack: track,
    }));

    // Update media session metadata for background playback
    if (mediaSession) {
      // Request high-resolution artwork for iOS retina displays (1024x1024)
      // Prefer album artwork if available, otherwise use track artwork
      const imageItemId = track.AlbumPrimaryImageTag && track.AlbumId ? track.AlbumId : track.Id;
      const albumArtUrl =
        track.AlbumPrimaryImageTag || track.ImageTags?.Primary
          ? $client.getImageUrl(imageItemId, 'Primary', { maxWidth: RETINA_IMAGE_SIZE })
          : undefined;

      mediaSession.updateMetadata({
        title: track.Name,
        artist: track.Artists?.join(', ') || 'Unknown Artist',
        album: track.Album || 'Unknown Album',
        artwork: albumArtUrl,
      });

      mediaSession.setPlaybackState('playing');
    }
  } catch (error) {
    const err = error instanceof Error ? error : new Error(String(error));

    // Ignore "Load cancelled" errors - these are expected during rapid track switching
    if (err.message.includes('Load cancelled')) {
      log.debug('Track load cancelled (user switched tracks)');
      return; // Silent cancellation - do not throw or update error state
    }

    // Only update error state if operation is still current
    if (currentLoadOperation === operationId) {
      log.error('Play error:', error);
      playerStore.update(s => ({
        ...s,
        isPlaying: false,
        isLoading: false,
        error: err,
      }));
    }
    throw error;
  }
}

async function playAlbum(tracks: AudioItem[]): Promise<void> {
  if (tracks.length === 0) {
    return;
  }

  const state = get(playerStore);
  state.queue.setQueue(tracks);

  const firstTrack = state.queue.getCurrentItem();
  if (firstTrack) {
    await playTrackFromQueue(firstTrack);
  }
}

/**
 * Play tracks from a specific position in the album
 * Sets the full album as queue but starts playing from the specified index
 */
async function playFromAlbum(tracks: AudioItem[], startIndex: number): Promise<void> {
  if (tracks.length === 0 || startIndex < 0 || startIndex >= tracks.length) {
    return;
  }

  const state = get(playerStore);
  // Set the full album as queue
  state.queue.setQueue(tracks);

  // Jump to the specified track index
  const track = state.queue.jumpTo(startIndex);

  // Play the track from queue (preserves queue)
  if (track) {
    await playTrackFromQueue(track);
  }
}

/**
 * Add track to queue
 */
function addToQueue(track: AudioItem): void {
  const state = get(playerStore);
  state.queue.addToQueue(track);
}

/**
 * Add multiple tracks to queue
 */
function addMultipleToQueue(tracks: AudioItem[]): void {
  const state = get(playerStore);
  state.queue.addMultipleToQueue(tracks);
}

/**
 * Pause playback
 */
function pause(): void {
  if (!audioEngine) return;

  audioEngine.pause();

  const state = get(playerStore);
  const timing = get(playerTimingStore);
  if (state.state && state.currentTrack) {
    state.state.setStatus('paused');
    state.state.setCurrentTime(timing.currentTime);
    void state.state.reportPlaybackProgress();
  }

  // Update media session state
  if (mediaSession) {
    mediaSession.setPlaybackState('paused');
  }

  playerStore.update(s => ({ ...s, isPlaying: false }));
}

/**
 * Resume playback
 */
async function resume(): Promise<void> {
  if (!audioEngine) {
    const err = new Error('Audio engine not available');
    playerStore.update(s => ({ ...s, error: err }));
    throw err;
  }

  const state = get(playerStore);

  // Validate that a track is loaded before resuming
  if (!state.currentTrack) {
    throw new Error('No track loaded - cannot resume');
  }

  // Validate that the track is actually loaded in the audio engine
  const duration = audioEngine.getDuration();
  if (!duration || duration === 0) {
    throw new Error('Track not loaded in audio engine');
  }

  try {
    // Clear previous errors
    playerStore.update(s => ({ ...s, error: null }));

    // Synchronize volume with store before resuming playback
    audioEngine.setVolume(state.volume);

    await audioEngine.play();
    playerStore.update(s => ({ ...s, isPlaying: true }));

    // Immediately report the 'playing' status to the server
    if (state.state && state.currentTrack) {
      state.state.setStatus('playing');
      void state.state.reportPlaybackProgress();
    }

    // Update media session state
    if (mediaSession) {
      mediaSession.setPlaybackState('playing');
    }
  } catch (error) {
    const err = error instanceof Error ? error : new Error('Failed to resume playback');
    log.error('Resume error:', error);
    playerStore.update(s => ({ ...s, error: err, isPlaying: false }));
    throw error;
  }
}

/**
 * Toggle play/pause
 */
async function togglePlayPause(): Promise<void> {
  const state = get(playerStore);

  if (state.isPlaying) {
    pause();
  } else {
    await resume();
  }
}

/**
 * Stop playback
 */
function stop(): void {
  if (!audioEngine) return;

  audioEngine.pause();

  const state = get(playerStore);
  const timing = get(playerTimingStore);
  if (state.state && state.currentTrack) {
    state.state.setStatus('stopped');
    state.state.setCurrentTime(timing.currentTime);
    void state.state.reportPlaybackStop();
    state.state.reset();
  }

  // Clear media session
  if (mediaSession) {
    mediaSession.clearMetadata();
    mediaSession.setPlaybackState('none');
  }

  playerStore.update(s => ({
    ...s,
    isPlaying: false,
    currentTrack: null,
    currentTime: 0,
  }));
}

/**
 * Play next track in queue
 */
async function next(): Promise<void> {
  const state = get(playerStore);
  const nextTrack = state.queue.next();

  if (nextTrack) {
    await playTrackFromQueue(nextTrack);
  } else {
    // No more tracks, stop playback
    stop();
  }
}

/**
 * Play previous track in queue
 */
async function previous(): Promise<void> {
  const state = get(playerStore);
  const timing = get(playerTimingStore);

  // If we're more than threshold seconds in, restart current track
  if (timing.currentTime > RESTART_THRESHOLD_SECONDS) {
    seek(0);
    return;
  }

  const prevTrack = state.queue.previous();

  if (prevTrack) {
    await playTrackFromQueue(prevTrack);
  }
}

/**
 * Seek to position in current track
 */
function seek(seconds: number): void {
  if (!audioEngine) return;

  try {
    audioEngine.seek(seconds);
    playerStore.update(s => ({ ...s, currentTime: seconds, error: null }));
  } catch (error) {
    const err = error instanceof Error ? error : new Error('Seek failed');
    log.error('Seek error:', error);
    playerStore.update(s => ({ ...s, error: err }));
  }
}

function setVolume(level: number): void {
  if (!audioEngine) return;

  audioEngine.setVolume(level);
  // Keep telemetry in sync with UI volume
  if (playbackState) {
    playbackState.setVolume(level);
  }
  persistVolume(level);
  playerStore.update(s => ({ ...s, volume: level }));
}

/**
 * Toggle shuffle mode
 */
function toggleShuffle(): void {
  const state = get(playerStore);
  state.queue.toggleShuffleMode();
  const newShuffle = state.queue.getShuffleMode() === 'on';

  playerStore.update(s => ({ ...s, isShuffle: newShuffle }));
}

function setShuffle(enabled: boolean): void {
  const state = get(playerStore);
  state.queue.setShuffleMode(enabled ? 'on' : 'off');

  playerStore.update(s => ({ ...s, isShuffle: enabled }));
}

/**
 * Set repeat mode
 */
function setRepeatMode(mode: RepeatMode): void {
  const state = get(playerStore);
  state.queue.setRepeatMode(mode);

  playerStore.update(s => ({ ...s, repeatMode: mode }));
}

/**
 * Toggle repeat mode (off -> all -> one -> off)
 */
function toggleRepeat(): void {
  const state = get(playerStore);
  state.queue.toggleRepeatMode();
  const newMode = state.queue.getRepeatMode();

  playerStore.update(s => ({ ...s, repeatMode: newMode }));
}

/**
 * Get current queue
 */
function getQueue(): AudioItem[] {
  const state = get(playerStore);
  return state.queue.getAllItems();
}

/**
 * Clear queue
 */
function clearQueue(): void {
  const state = get(playerStore);
  state.queue.clear();
  stop();
}

/**
 * Remove track from queue by index
 */
function removeFromQueue(index: number): void {
  const state = get(playerStore);
  state.queue.removeAt(index);
}

// Derived stores with manual set to avoid safe_not_equal false positives on objects
let cachedCurrentTrack: AudioItem | null = null;
export const currentTrack = derived(
  playerStore,
  ($player, set) => {
    if ($player.currentTrack !== cachedCurrentTrack) {
      cachedCurrentTrack = $player.currentTrack;
      set(cachedCurrentTrack);
    }
  },
  null as AudioItem | null
);

// High-frequency timing stores (RAF-throttled, independent from playerStore)
export const currentTime = derived(playerTimingStore, $timing => $timing.currentTime);
export const duration = derived(playerTimingStore, $timing => $timing.duration);

export const volume = derived(playerStore, $player => $player.volume);
export const isPlaying = derived(playerStore, $player => $player.isPlaying);
export const isLoading = derived(playerStore, $player => $player.isLoading);
export const isShuffle = derived(playerStore, $player => $player.isShuffle);
export const repeatMode = derived(playerStore, $player => $player.repeatMode);
export const error = derived(playerStore, $player => $player.error);

// Memoized queue items with manual set to avoid safe_not_equal false positives
let cachedQueueItems: AudioItem[] = [];
let cachedQueueLength = 0;
export const queueItems = derived(
  playerStore,
  ($player, set) => {
    const items = $player.queue.getAllItems();
    if (items.length !== cachedQueueLength) {
      cachedQueueItems = items;
      cachedQueueLength = items.length;
      set(cachedQueueItems);
    }
  },
  [] as AudioItem[]
);

/**
 * Stop UI update loop (RAF-based timing updates)
 * Safe to call on background/unmount - does NOT stop audio playback
 */
function stopUiLoop(): void {
  if (rafId !== null) {
    cancelAnimationFrame(rafId);
    rafId = null;
  }
}

/**
 * Resume AudioContext if suspended (mobile background playback fix)
 * Call this when app returns to foreground (visibilitychange, focus events)
 */
async function resumeAudioContext(): Promise<void> {
  if (audioEngine?.getAudioContext) {
    const ctx = audioEngine.getAudioContext();
    if (ctx?.state === 'suspended') {
      try {
        await ctx.resume();
        log.info('[Player] AudioContext resumed after foreground transition');
      } catch (err) {
        log.error('[Player] Failed to resume AudioContext:', err);
      }
    }
  }
}

type CleanupReason = 'ui' | 'background' | 'logout' | 'hard-stop';

/**
 * Cleanup player resources
 * Reason determines cleanup scope:
 * - 'ui' | 'background': Stop UI updates only (audio keeps playing)
 * - 'logout' | 'hard-stop': Full cleanup including audio stop
 */
function cleanup(reason: CleanupReason = 'ui'): void {
  // Always stop UI update loop
  stopUiLoop();

  // NEVER stop audio on background/unmount - only UI cleanup
  if (reason === 'ui' || reason === 'background') return;

  // Only explicit teardown cases stop audio playback
  if (audioEngine) {
    audioEngine.pause();
  }

  // Dispose playback state timer
  if (playbackState) {
    playbackState.dispose();
    playbackState = null;
  }

  // Clear MediaSession handlers on logout (prevents stale handlers)
  if (mediaSession) {
    mediaSession.reset();
  }

  // Reset player state (preserve volume setting)
  const currentVolume = get(playerStore).volume;
  playerStore.set({ ...initialState, volume: currentVolume });
}

/**
 * Full disposal of player resources (for app shutdown)
 * Call this when the application is being destroyed
 */
function dispose(): void {
  // Stop UI updates
  stopUiLoop();

  // Cleanup audio event listeners
  audioEventCleanups.forEach(cleanup => cleanup());
  audioEventCleanups = [];

  // Unsubscribe from client store
  if (clientUnsubscribe) {
    clientUnsubscribe();
    clientUnsubscribe = null;
  }

  // Unsubscribe from karaoke store
  if (karaokeUnsubscribe) {
    karaokeUnsubscribe();
    karaokeUnsubscribe = null;
  }

  // Dispose playback state
  if (playbackState) {
    playbackState.dispose();
    playbackState = null;
  }

  // Reset and dispose MediaSession
  if (mediaSession) {
    mediaSession.reset();
  }

  // Dispose AudioEngine (releases all browser resources)
  if (audioEngine) {
    audioEngine.dispose();
    audioEngine = null;
  }
}

export const player = {
  subscribe: playerStore.subscribe,
  play,
  playAlbum,
  playFromAlbum,
  addToQueue,
  addMultipleToQueue,
  pause,
  resume,
  togglePlayPause,
  stop,
  next,
  previous,
  seek,
  setVolume,
  toggleShuffle,
  setShuffle,
  setRepeatMode,
  toggleRepeat,
  getQueue,
  clearQueue,
  removeFromQueue,
  cleanup,
  dispose,
  stopUiLoop,
  resumeAudioContext,
};

// Set up media session action handlers (browser only)
// Placed after player object definition to avoid closure issues with HMR
if (mediaSession) {
  mediaSession.setActionHandlers({
    onPlay: () => {
      player.resume().catch(error => {
        log.error('Media Session play error', error);
      });
    },
    onPause: () => {
      player.pause();
    },
    onNext: () => {
      player.next().catch(error => {
        log.error('Media Session next error', error);
      });
    },
    onPrevious: () => {
      player.previous().catch(error => {
        log.error('Media Session previous error', error);
      });
    },
    onSeek: seconds => {
      player.seek(seconds);
    },
  });
}

// Expose player store for E2E tests
declare global {
  interface Window {
    __playerStore__?: {
      audioEngine: HTML5AudioEngine | null;
      volume: number;
      isPlaying: boolean;
    };
  }
}

if (browser) {
  window.__playerStore__ = {
    get audioEngine() {
      return audioEngine;
    },
    get volume() {
      return get(playerStore).volume;
    },
    get isPlaying() {
      return get(playerStore).isPlaying;
    },
  };
}
