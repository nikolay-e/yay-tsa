/**
 * Player store
 * Manages playback state, queue, and audio engine
 */

import { writable, derived, get } from 'svelte/store';
import { browser, dev } from '$app/environment';
import {
  PlaybackQueue,
  PlaybackState,
  type AudioItem,
  type RepeatMode
} from '@jellyfin-mini/core';
import { HTML5AudioEngine, MediaSessionManager } from '@jellyfin-mini/platform';
import { client } from './auth.js';

// Playback constants
const RESTART_THRESHOLD_SECONDS = 3; // Seconds before "previous" restarts current track
const RETINA_IMAGE_SIZE = 1024; // High-resolution artwork for retina displays

interface PlayerState {
  queue: PlaybackQueue;
  state: PlaybackState | null;
  audioEngine: HTML5AudioEngine | null;
  currentTrack: AudioItem | null;
  currentTime: number;
  duration: number;
  volume: number;
  isPlaying: boolean;
  isLoading: boolean;
  isShuffle: boolean;
  repeatMode: RepeatMode;
  error: string | null;
}

// Initialize core components
const queue = new PlaybackQueue();
// Audio engine and media session are only available in browser
let audioEngine: HTML5AudioEngine | null = null;
let mediaSession: MediaSessionManager | null = null;

// Initialize audio engine and media session in browser only
if (browser) {
  audioEngine = new HTML5AudioEngine();
  mediaSession = new MediaSessionManager();

  // Log media session support (development only)
  if (dev) {
    if (mediaSession.supported()) {
      console.info('[Media Session] Supported - background playback enabled');
    } else {
      console.info('[Media Session] Not supported - background playback limited');
    }
  }
}

// Initialize PlaybackState when client is available
let playbackState: PlaybackState | null = null;

const initialState: PlayerState = {
  queue,
  state: null,
  audioEngine,
  currentTrack: null,
  currentTime: 0,
  duration: 0,
  volume: 0.7,
  isPlaying: false,
  isLoading: false,
  isShuffle: false,
  repeatMode: 'off',
  error: null
};

const playerStore = writable<PlayerState>(initialState);

// Subscribe to client changes AFTER playerStore is created
client.subscribe(($client) => {
  if ($client) {
    playbackState = new PlaybackState($client);
    playerStore.update((state) => ({ ...state, state: playbackState }));
  } else {
    playbackState = null;
    playerStore.update((state) => ({ ...state, state: null }));
  }
});

// Set up audio engine event listeners (browser only)
if (audioEngine) {
  audioEngine.setVolume(0.7);

  // Time update handler
  audioEngine.onTimeUpdate((time) => {
    const duration = audioEngine ? audioEngine.getDuration() : 0;

    playerStore.update((state: PlayerState) => ({
      ...state,
      currentTime: time,
      duration
    }));

    // Update PlaybackState time (it will auto-report every 10s)
    const state = get(playerStore);
    if (state.state && state.currentTrack && audioEngine) {
      state.state.setCurrentTime(time);
    }

    // Update media session position state for lock screen seek bar
    if (mediaSession && duration > 0) {
      mediaSession.updatePositionState(duration, time);
    }
  });

  // Track ended handler - auto advance to next
  audioEngine.onEnded(() => {
    const state = get(playerStore);

    // Report completion
    if (state.state && state.currentTrack) {
      state.state.setStatus('stopped');
      state.state.setCurrentTime(state.currentTime);
      void state.state.reportPlaybackStop();
    }

    // Auto-advance to next track
    next().catch((error) => {
      console.error('Auto-advance error:', error);
      playerStore.update((s) => ({
        ...s,
        isPlaying: false,
        error: (error as Error).message
      }));
    });
  });

  // Error handler
  audioEngine.onError((error) => {
    console.error('Audio playback error:', error);
    playerStore.update((state) => ({
      ...state,
      isPlaying: false,
      isLoading: false,
      error: error.message
    }));
  });

  // Loading state handler
  audioEngine.onLoading((isLoading) => {
    playerStore.update((state) => ({
      ...state,
      isLoading
    }));
  });
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
  const state = get(playerStore);
  const $client = get(client);

  if (!$client) {
    throw new Error('Not authenticated');
  }

  if (!audioEngine) {
    throw new Error('Audio engine not available');
  }

  try {
    // Get stream URL
    const streamUrl = $client.getStreamUrl(track.Id);

    // Load and play
    playerStore.update((s) => ({ ...s, isLoading: true, error: null, currentTrack: track }));

    await audioEngine.load(streamUrl);
    await audioEngine.play();

    // Report playback start
    if (state.state) {
      state.state.setCurrentItem(track);
      state.state.setStatus('playing');
      await state.state.reportPlaybackStart();
    }

    playerStore.update((s) => ({
      ...s,
      isPlaying: true,
      isLoading: false,
      currentTrack: track
    }));

    // Update media session metadata for background playback
    if (mediaSession) {
      // Request high-resolution artwork for iOS retina displays (1024x1024)
      // Prefer album artwork if available, otherwise use track artwork
      const imageItemId = track.AlbumPrimaryImageTag && track.AlbumId
        ? track.AlbumId
        : track.Id;
      const albumArtUrl = (track.AlbumPrimaryImageTag || track.ImageTags?.Primary)
        ? $client.getImageUrl(imageItemId, 'Primary', { maxWidth: RETINA_IMAGE_SIZE })
        : undefined;

      mediaSession.updateMetadata({
        title: track.Name,
        artist: track.Artists?.join(', ') || 'Unknown Artist',
        album: track.Album || 'Unknown Album',
        artwork: albumArtUrl
      });

      mediaSession.setPlaybackState('playing');
    }
  } catch (error) {
    console.error('Play error:', error);
    playerStore.update((s) => ({
      ...s,
      isPlaying: false,
      isLoading: false,
      error: (error as Error).message
    }));
    throw error;
  }
}

/**
 * Play an album (set queue and play first track)
 */
async function playAlbum(tracks: AudioItem[]): Promise<void> {
  if (tracks.length === 0) {
    return;
  }

  const state = get(playerStore);
  state.queue.setQueue(tracks);

  // Play first track from queue (preserves queue)
  await playTrackFromQueue(tracks[0]);
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
  if (state.state && state.currentTrack) {
    state.state.setStatus('paused');
    state.state.setCurrentTime(state.currentTime);
    void state.state.reportPlaybackProgress();
  }

  // Update media session state
  if (mediaSession) {
    mediaSession.setPlaybackState('paused');
  }

  playerStore.update((s) => ({ ...s, isPlaying: false }));
}

/**
 * Resume playback
 */
async function resume(): Promise<void> {
  if (!audioEngine) {
    throw new Error('Audio engine not available');
  }

  try {
    await audioEngine.play();
    playerStore.update((s) => ({ ...s, isPlaying: true }));

    // Immediately report the 'playing' status to the server
    const state = get(playerStore);
    if (state.state && state.currentTrack) {
      state.state.setStatus('playing');
      void state.state.reportPlaybackProgress();
    }

    // Update media session state
    if (mediaSession) {
      mediaSession.setPlaybackState('playing');
    }
  } catch (error) {
    console.error('Resume error:', error);
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
  if (state.state && state.currentTrack) {
    state.state.setStatus('stopped');
    state.state.setCurrentTime(state.currentTime);
    void state.state.reportPlaybackStop();
    state.state.reset();
  }

  // Clear media session
  if (mediaSession) {
    mediaSession.clearMetadata();
    mediaSession.setPlaybackState('none');
  }

  playerStore.update((s) => ({
    ...s,
    isPlaying: false,
    currentTrack: null,
    currentTime: 0
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

  // If we're more than threshold seconds in, restart current track
  if (state.currentTime > RESTART_THRESHOLD_SECONDS) {
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

  audioEngine.seek(seconds);
  playerStore.update((s) => ({ ...s, currentTime: seconds }));
}

/**
 * Set volume (0.0 to 1.0)
 */
function setVolume(level: number): void {
  if (!audioEngine) return;

  audioEngine.setVolume(level);
  playerStore.update((s) => ({ ...s, volume: level }));
}

/**
 * Toggle shuffle mode
 */
function toggleShuffle(): void {
  const state = get(playerStore);
  state.queue.toggleShuffleMode();
  const newShuffle = state.queue.getShuffleMode() === 'on';

  playerStore.update((s) => ({ ...s, isShuffle: newShuffle }));
}

/**
 * Set repeat mode
 */
function setRepeatMode(mode: RepeatMode): void {
  const state = get(playerStore);
  state.queue.setRepeatMode(mode);

  playerStore.update((s) => ({ ...s, repeatMode: mode }));
}

/**
 * Toggle repeat mode (off -> all -> one -> off)
 */
function toggleRepeat(): void {
  const state = get(playerStore);
  state.queue.toggleRepeatMode();
  const newMode = state.queue.getRepeatMode();

  playerStore.update((s) => ({ ...s, repeatMode: newMode }));
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

// Derived stores
export const currentTrack = derived(playerStore, ($player) => $player.currentTrack);
export const currentTime = derived(playerStore, ($player) => $player.currentTime);
export const duration = derived(playerStore, ($player) => $player.duration);
export const volume = derived(playerStore, ($player) => $player.volume);
export const isPlaying = derived(playerStore, ($player) => $player.isPlaying);
export const isLoading = derived(playerStore, ($player) => $player.isLoading);
export const isShuffle = derived(playerStore, ($player) => $player.isShuffle);
export const repeatMode = derived(playerStore, ($player) => $player.repeatMode);
export const error = derived(playerStore, ($player) => $player.error);
export const queueItems = derived(playerStore, ($player) => $player.queue.getAllItems());

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
  setRepeatMode,
  toggleRepeat,
  getQueue,
  clearQueue,
  removeFromQueue
};

// Set up media session action handlers (browser only)
// Placed after player object definition to avoid closure issues with HMR
if (mediaSession) {
  mediaSession.setActionHandlers({
    onPlay: () => {
      player.resume().catch((error) => {
        if (dev) console.error('Media Session play error:', error);
      });
    },
    onPause: () => {
      player.pause();
    },
    onNext: () => {
      player.next().catch((error) => {
        if (dev) console.error('Media Session next error:', error);
      });
    },
    onPrevious: () => {
      player.previous().catch((error) => {
        if (dev) console.error('Media Session previous error:', error);
      });
    },
    onSeek: (seconds) => {
      player.seek(seconds);
    }
  });
}
