import { writable, derived, get } from 'svelte/store';
import { browser } from '$app/environment';
import { PinkNoiseGenerator } from '@yaytsa/platform';
import { player, volume as volumeStore } from './player.store.js';
import { createLogger } from '@yaytsa/core';

const log = createLogger('SleepTimer');

const SLEEP_TIMER_SETTINGS_KEY = 'yaytsa_sleep_timer_settings';
const PINK_NOISE_VOLUME_RATIO = 0.08;

export type SleepTimerPhase = 'idle' | 'music' | 'crossfade-to-noise' | 'noise' | 'stopped';

export interface SleepTimerConfig {
  musicDurationMs: number;
  noiseDurationMs: number;
  crossfadeDurationMs: number;
  enableNoise: boolean;
}

interface SleepTimerState {
  isActive: boolean;
  phase: SleepTimerPhase;
  config: SleepTimerConfig;
  startedAt: number | null;
  phaseStartedAt: number | null;
  remainingMs: number;
  phaseRemainingMs: number;
}

const DEFAULT_CONFIG: SleepTimerConfig = {
  musicDurationMs: 60 * 60 * 1000, // 1 hour
  noiseDurationMs: 30 * 60 * 1000, // 30 minutes
  crossfadeDurationMs: 10 * 60 * 1000, // 10 minutes
  enableNoise: true,
};

const PRESETS: Record<string, Partial<SleepTimerConfig>> = {
  // With noise (default)
  '1h+10m+30m': {
    musicDurationMs: 60 * 60 * 1000,
    crossfadeDurationMs: 10 * 60 * 1000,
    noiseDurationMs: 30 * 60 * 1000,
    enableNoise: true,
  },
  '30m+5m+15m': {
    musicDurationMs: 30 * 60 * 1000,
    crossfadeDurationMs: 5 * 60 * 1000,
    noiseDurationMs: 15 * 60 * 1000,
    enableNoise: true,
  },
  '1m+1m+1m': {
    musicDurationMs: 1 * 60 * 1000,
    crossfadeDurationMs: 1 * 60 * 1000,
    noiseDurationMs: 1 * 60 * 1000,
    enableNoise: true,
  },
  // Without noise (fade only)
  '1h+10m': {
    musicDurationMs: 60 * 60 * 1000,
    crossfadeDurationMs: 10 * 60 * 1000,
    noiseDurationMs: 0,
    enableNoise: false,
  },
  '30m+5m': {
    musicDurationMs: 30 * 60 * 1000,
    crossfadeDurationMs: 5 * 60 * 1000,
    noiseDurationMs: 0,
    enableNoise: false,
  },
  '1m+1m': {
    musicDurationMs: 1 * 60 * 1000,
    crossfadeDurationMs: 1 * 60 * 1000,
    noiseDurationMs: 0,
    enableNoise: false,
  },
};

function loadPersistedConfig(): SleepTimerConfig {
  if (!browser) return DEFAULT_CONFIG;
  try {
    const stored = localStorage.getItem(SLEEP_TIMER_SETTINGS_KEY);
    if (stored) {
      const parsed = JSON.parse(stored) as Partial<SleepTimerConfig>;
      return { ...DEFAULT_CONFIG, ...parsed };
    }
  } catch {
    // Ignore parse errors
  }
  return DEFAULT_CONFIG;
}

function persistConfig(config: SleepTimerConfig): void {
  if (!browser) return;
  try {
    localStorage.setItem(SLEEP_TIMER_SETTINGS_KEY, JSON.stringify(config));
  } catch {
    // Ignore storage errors
  }
}

const initialState: SleepTimerState = {
  isActive: false,
  phase: 'idle',
  config: loadPersistedConfig(),
  startedAt: null,
  phaseStartedAt: null,
  remainingMs: 0,
  phaseRemainingMs: 0,
};

const sleepTimerStore = writable<SleepTimerState>(initialState);

let tickIntervalId: ReturnType<typeof setInterval> | null = null;
let pinkNoiseGenerator: PinkNoiseGenerator | null = null;
let savedMusicVolume: number = 0.7;
let currentFadeCancel: (() => void) | null = null;

function clearTickInterval(): void {
  if (tickIntervalId) {
    clearInterval(tickIntervalId);
    tickIntervalId = null;
  }
}

function cancelCurrentFade(): void {
  if (currentFadeCancel) {
    currentFadeCancel();
    currentFadeCancel = null;
  }
}

async function transitionToPhase(newPhase: SleepTimerPhase): Promise<void> {
  log.info(`[Sleep Timer] Transitioning to phase: ${newPhase}`);

  sleepTimerStore.update(s => ({
    ...s,
    phase: newPhase,
    phaseStartedAt: Date.now(),
  }));

  switch (newPhase) {
    case 'crossfade-to-noise':
      await startCrossfadeToNoise();
      break;
    case 'noise':
      // Start gradual fade over entire noise duration
      await startGradualNoiseFade();
      break;
    case 'stopped':
      stopSleepTimer();
      break;
  }
}

async function startCrossfadeToNoise(): Promise<void> {
  const state = get(sleepTimerStore);
  const { crossfadeDurationMs, enableNoise, noiseDurationMs } = state.config;

  savedMusicVolume = get(volumeStore);
  const targetNoiseVolume = savedMusicVolume * PINK_NOISE_VOLUME_RATIO;

  const playerState = get(player);
  const audioEngine = playerState.audioEngine;

  try {
    if (enableNoise && noiseDurationMs > 0) {
      // Crossfade with pink noise
      log.info(
        `[Sleep Timer] Starting crossfade with noise - Music volume: ${savedMusicVolume.toFixed(3)}, Pink noise target: ${targetNoiseVolume.toFixed(3)}`
      );

      pinkNoiseGenerator ??= new PinkNoiseGenerator();

      // Get shared AudioContext from music player to avoid creating duplicate contexts
      // This ensures both music and pink noise can be controlled from the same context
      const sharedContext = audioEngine?.getAudioContext?.() || null;

      // Resume AudioContext if suspended (required for autoplay policies)
      if (sharedContext?.state === 'suspended') {
        log.info('[Sleep Timer] Resuming suspended AudioContext for pink noise');
        await sharedContext.resume();
      }

      await pinkNoiseGenerator.start({
        initialVolume: 0,
        audioContext: sharedContext || undefined,
      });

      if (audioEngine?.fadeVolume) {
        // Pink noise fades in for 75% of crossfade duration, then holds at target volume
        // This prevents volume dip: when music reaches 25%, pink noise is already at 25%
        const noiseFadeInDuration = crossfadeDurationMs * 0.75;

        const musicFade = audioEngine.fadeVolume(savedMusicVolume, 0, crossfadeDurationMs);
        const noiseFade = pinkNoiseGenerator.fadeVolume(0, targetNoiseVolume, noiseFadeInDuration);

        currentFadeCancel = () => {
          musicFade.cancel();
          noiseFade.cancel();
        };

        // Wait for both fades to complete (noise finishes earlier, then holds)
        await Promise.all([musicFade.promise, noiseFade.promise]);
        currentFadeCancel = null;

        player.pause();

        await transitionToPhase('noise');
      } else {
        player.setVolume(0);
        player.pause();
        pinkNoiseGenerator.setVolume(targetNoiseVolume);
        await transitionToPhase('noise');
      }
    } else {
      // Fade out music only (no pink noise)
      log.info(
        `[Sleep Timer] Starting fade out - Music volume: ${savedMusicVolume.toFixed(3)} → 0`
      );

      if (audioEngine?.fadeVolume) {
        const musicFade = audioEngine.fadeVolume(savedMusicVolume, 0, crossfadeDurationMs);

        currentFadeCancel = musicFade.cancel;

        await musicFade.promise;
        currentFadeCancel = null;

        player.pause();
        stopSleepTimer();
      } else {
        player.setVolume(0);
        player.pause();
        stopSleepTimer();
      }
    }
  } catch (error) {
    log.error('[Sleep Timer] Crossfade error:', error);
    stopSleepTimer();
  }
}

async function startGradualNoiseFade(): Promise<void> {
  const state = get(sleepTimerStore);
  const { noiseDurationMs } = state.config;

  if (!pinkNoiseGenerator) {
    stopSleepTimer();
    return;
  }

  try {
    const startVolume = savedMusicVolume * PINK_NOISE_VOLUME_RATIO;
    const fade = pinkNoiseGenerator.fadeVolume(startVolume, 0, noiseDurationMs);

    currentFadeCancel = fade.cancel;
    await fade.promise;
    currentFadeCancel = null;

    stopSleepTimer();
  } catch (error) {
    log.error('[Sleep Timer] Gradual noise fade error:', error);
    stopSleepTimer();
  }
}

function tick(): void {
  const state = get(sleepTimerStore);
  if (!state.isActive || !state.startedAt || !state.phaseStartedAt) return;

  const now = Date.now();
  const { musicDurationMs, noiseDurationMs, crossfadeDurationMs, enableNoise } = state.config;

  // Calculate total and phase remaining time
  // Total = music + crossfade + (noise only if enabled)
  const totalDuration =
    enableNoise && noiseDurationMs > 0
      ? musicDurationMs + crossfadeDurationMs + noiseDurationMs
      : musicDurationMs + crossfadeDurationMs;
  const totalElapsed = now - state.startedAt;
  const remainingMs = Math.max(0, totalDuration - totalElapsed);

  const phaseElapsed = now - state.phaseStartedAt;
  let phaseDuration = 0;
  let nextPhase: SleepTimerPhase | null = null;

  switch (state.phase) {
    case 'music':
      phaseDuration = musicDurationMs;
      nextPhase = 'crossfade-to-noise';
      break;
    case 'crossfade-to-noise':
      phaseDuration = crossfadeDurationMs;
      nextPhase = enableNoise && noiseDurationMs > 0 ? 'noise' : 'stopped';
      break;
    case 'noise':
      // Noise phase includes gradual fade - transition handled by startGradualNoiseFade
      phaseDuration = noiseDurationMs;
      nextPhase = null;
      break;
  }

  const phaseRemainingMs = Math.max(0, phaseDuration - phaseElapsed);

  sleepTimerStore.update(s => ({
    ...s,
    remainingMs,
    phaseRemainingMs,
  }));

  // Check if phase should transition (noise phase transitions via fade completion)
  if (phaseElapsed >= phaseDuration && nextPhase) {
    void transitionToPhase(nextPhase);
  }
}

function start(config?: Partial<SleepTimerConfig>): void {
  const state = get(sleepTimerStore);

  // Merge config with defaults and current settings
  const newConfig: SleepTimerConfig = {
    ...state.config,
    ...config,
  };

  // Persist config
  persistConfig(newConfig);

  // Calculate total duration (music only, or music + crossfade + noise if noise enabled)
  const totalDuration =
    newConfig.noiseDurationMs > 0
      ? newConfig.musicDurationMs + newConfig.crossfadeDurationMs + newConfig.noiseDurationMs
      : newConfig.musicDurationMs;

  const now = Date.now();

  sleepTimerStore.set({
    isActive: true,
    phase: 'music',
    config: newConfig,
    startedAt: now,
    phaseStartedAt: now,
    remainingMs: totalDuration,
    phaseRemainingMs: newConfig.musicDurationMs,
  });

  clearTickInterval();
  tickIntervalId = setInterval(tick, 100);

  log.info('Started', {
    musicDurationMs: newConfig.musicDurationMs,
    noiseDurationMs: newConfig.noiseDurationMs,
    crossfadeDurationMs: newConfig.crossfadeDurationMs,
    enableNoise: newConfig.enableNoise,
  });
}

function stopSleepTimer(): void {
  const currentPhase = get(sleepTimerStore).phase;
  const volumeBeforeRestore = get(volumeStore);

  clearTickInterval();
  cancelCurrentFade();

  if (pinkNoiseGenerator) {
    pinkNoiseGenerator.dispose();
    pinkNoiseGenerator = null;
  }

  // Restore music volume to saved value
  // This ensures player is ready for next playback
  player.setVolume(savedMusicVolume);

  const volumeAfterRestore = get(volumeStore);

  // Player is already paused after timer completion
  // User can manually resume playback at restored volume

  sleepTimerStore.set({
    ...initialState,
    config: get(sleepTimerStore).config,
  });

  log.info('Stopped', {
    phase: currentPhase,
    savedVolume: savedMusicVolume,
    volumeBefore: volumeBeforeRestore,
    volumeAfter: volumeAfterRestore,
  });
}

async function cancel(): Promise<void> {
  const state = get(sleepTimerStore);
  const volumeBeforeRestore = get(volumeStore);

  clearTickInterval();
  cancelCurrentFade();

  if (pinkNoiseGenerator) {
    pinkNoiseGenerator.dispose();
    pinkNoiseGenerator = null;
  }

  player.setVolume(savedMusicVolume);
  const volumeAfterRestore = get(volumeStore);

  log.info('Cancelled', {
    phase: state.phase,
    savedVolume: savedMusicVolume,
    volumeBefore: volumeBeforeRestore,
    volumeAfter: volumeAfterRestore,
  });

  if (state.phase !== 'music' && state.phase !== 'idle') {
    try {
      await player.resume();
      log.info('Resumed playback after cancellation');
    } catch (error) {
      // Music might have ended, that's okay
      log.warn('Failed to resume after cancellation', { error: String(error) });
    }
  }

  sleepTimerStore.set({
    ...initialState,
    config: state.config,
  });
}

function extendTime(additionalMs: number): void {
  sleepTimerStore.update(s => {
    if (!s.isActive) return s;

    const newMusicDuration = s.config.musicDurationMs + additionalMs;
    const newConfig = { ...s.config, musicDurationMs: newMusicDuration };

    // Only extend if still in music phase
    if (s.phase === 'music') {
      return {
        ...s,
        config: newConfig,
        remainingMs: s.remainingMs + additionalMs,
        phaseRemainingMs: s.phaseRemainingMs + additionalMs,
      };
    }

    return s;
  });
}

function applyPreset(presetName: string): void {
  const preset = PRESETS[presetName];
  if (preset) {
    const state = get(sleepTimerStore);
    const newConfig = { ...state.config, ...preset };
    sleepTimerStore.update(s => ({ ...s, config: newConfig }));
    persistConfig(newConfig);
  }
}

function updateConfig(config: Partial<SleepTimerConfig>): void {
  sleepTimerStore.update(s => {
    const newConfig = { ...s.config, ...config };
    persistConfig(newConfig);
    return { ...s, config: newConfig };
  });
}

// Derived stores
export const isActive = derived(sleepTimerStore, $s => $s.isActive);
export const phase = derived(sleepTimerStore, $s => $s.phase);
export const remainingMs = derived(sleepTimerStore, $s => $s.remainingMs);
export const config = derived(sleepTimerStore, $s => $s.config);

export function formatTimeRemaining(ms: number): string {
  const totalSeconds = Math.ceil(ms / 1000);
  const hours = Math.floor(totalSeconds / 3600);
  const minutes = Math.floor((totalSeconds % 3600) / 60);
  const seconds = totalSeconds % 60;

  if (hours > 0) {
    return `${hours}:${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}`;
  }
  return `${minutes}:${seconds.toString().padStart(2, '0')}`;
}

export const sleepTimer = {
  subscribe: sleepTimerStore.subscribe,
  start,
  cancel,
  extendTime,
  applyPreset,
  updateConfig,
  presets: PRESETS,
};

// Expose sleep timer state for E2E tests
declare global {
  interface Window {
    __sleepTimerStore__?: {
      isActive: boolean;
      phase: SleepTimerPhase;
      remainingMs: number;
    };
  }
}

let debugUnsubscribe: (() => void) | null = null;

if (browser) {
  debugUnsubscribe = sleepTimerStore.subscribe(state => {
    window.__sleepTimerStore__ = {
      isActive: state.isActive,
      phase: state.phase,
      remainingMs: state.remainingMs,
    };
  });

  // HMR cleanup - dispose subscription on module reload
  if (import.meta.hot) {
    import.meta.hot.dispose(() => {
      if (debugUnsubscribe) {
        debugUnsubscribe();
        debugUnsubscribe = null;
      }
    });
  }
}
