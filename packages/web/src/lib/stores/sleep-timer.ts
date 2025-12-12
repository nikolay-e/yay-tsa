import { writable, derived, get } from 'svelte/store';
import { browser } from '$app/environment';
import { PinkNoiseGenerator } from '@yaytsa/platform';
import { player, volume as volumeStore } from './player.js';
import { logger } from '../utils/logger.js';

const SLEEP_TIMER_SETTINGS_KEY = 'yaytsa_sleep_timer_settings';

export type SleepTimerPhase =
  | 'idle'
  | 'music'
  | 'crossfade-to-noise'
  | 'noise'
  | 'fade-out'
  | 'stopped';

export interface SleepTimerConfig {
  musicDurationMs: number;
  noiseDurationMs: number;
  crossfadeDurationMs: number;
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
  noiseDurationMs: 60 * 60 * 1000, // 1 hour
  crossfadeDurationMs: 5 * 60 * 1000, // 5 minutes
};

const PRESETS: Record<string, Partial<SleepTimerConfig>> = {
  '15min': { musicDurationMs: 15 * 60 * 1000, noiseDurationMs: 0 },
  '30min': { musicDurationMs: 30 * 60 * 1000, noiseDurationMs: 0 },
  '1h': { musicDurationMs: 60 * 60 * 1000, noiseDurationMs: 0 },
  '1h+1h': { musicDurationMs: 60 * 60 * 1000, noiseDurationMs: 60 * 60 * 1000 },
  '2h+1h': { musicDurationMs: 2 * 60 * 60 * 1000, noiseDurationMs: 60 * 60 * 1000 },
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
  logger.info(`[Sleep Timer] Transitioning to phase: ${newPhase}`);

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
      // Pink noise is now at full volume, music stopped
      break;
    case 'fade-out':
      await startFadeOut();
      break;
    case 'stopped':
      stopSleepTimer();
      break;
  }
}

async function startCrossfadeToNoise(): Promise<void> {
  const state = get(sleepTimerStore);
  const { crossfadeDurationMs } = state.config;

  // Save current volume for restoration if cancelled
  savedMusicVolume = get(volumeStore);

  // Initialize pink noise generator if not already
  pinkNoiseGenerator ??= new PinkNoiseGenerator();

  try {
    // Start pink noise at volume 0
    await pinkNoiseGenerator.start({ initialVolume: 0 });

    // Get the audio engine from player state
    const playerState = get(player);
    const audioEngine = playerState.audioEngine;

    if (audioEngine?.fadeVolume) {
      // Crossfade: music fades out, pink noise fades in
      const musicFade = audioEngine.fadeVolume(savedMusicVolume, 0, crossfadeDurationMs);
      const noiseFade = pinkNoiseGenerator.fadeVolume(0, savedMusicVolume, crossfadeDurationMs);

      currentFadeCancel = () => {
        musicFade.cancel();
        noiseFade.cancel();
      };

      await Promise.all([musicFade.promise, noiseFade.promise]);
      currentFadeCancel = null;

      // Stop music playback after crossfade completes
      player.pause();

      // Transition to noise phase
      await transitionToPhase('noise');
    } else {
      // Fallback: instant switch if fadeVolume not available
      player.setVolume(0);
      player.pause();
      pinkNoiseGenerator.setVolume(savedMusicVolume);
      await transitionToPhase('noise');
    }
  } catch (error) {
    logger.error('[Sleep Timer] Crossfade error:', error);
    stopSleepTimer();
  }
}

async function startFadeOut(): Promise<void> {
  const state = get(sleepTimerStore);
  const { crossfadeDurationMs } = state.config;

  if (!pinkNoiseGenerator) {
    stopSleepTimer();
    return;
  }

  try {
    const fade = pinkNoiseGenerator.fadeVolume(
      pinkNoiseGenerator.getVolume(),
      0,
      crossfadeDurationMs
    );

    currentFadeCancel = fade.cancel;
    await fade.promise;
    currentFadeCancel = null;

    // Final stop
    stopSleepTimer();
  } catch (error) {
    logger.error('[Sleep Timer] Fade out error:', error);
    stopSleepTimer();
  }
}

function tick(): void {
  const state = get(sleepTimerStore);
  if (!state.isActive || !state.startedAt || !state.phaseStartedAt) return;

  const now = Date.now();
  const { musicDurationMs, noiseDurationMs, crossfadeDurationMs } = state.config;

  // Calculate total and phase remaining time
  const totalDuration = musicDurationMs + noiseDurationMs + crossfadeDurationMs * 2;
  const totalElapsed = now - state.startedAt;
  const remainingMs = Math.max(0, totalDuration - totalElapsed);

  const phaseElapsed = now - state.phaseStartedAt;
  let phaseDuration = 0;
  let nextPhase: SleepTimerPhase | null = null;

  switch (state.phase) {
    case 'music':
      phaseDuration = musicDurationMs;
      nextPhase = noiseDurationMs > 0 ? 'crossfade-to-noise' : 'fade-out';
      break;
    case 'crossfade-to-noise':
      phaseDuration = crossfadeDurationMs;
      nextPhase = 'noise';
      break;
    case 'noise':
      phaseDuration = noiseDurationMs;
      nextPhase = 'fade-out';
      break;
    case 'fade-out':
      phaseDuration = crossfadeDurationMs;
      nextPhase = 'stopped';
      break;
  }

  const phaseRemainingMs = Math.max(0, phaseDuration - phaseElapsed);

  sleepTimerStore.update(s => ({
    ...s,
    remainingMs,
    phaseRemainingMs,
  }));

  // Check if phase should transition
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

  // Calculate total duration
  const totalDuration =
    newConfig.musicDurationMs + newConfig.noiseDurationMs + newConfig.crossfadeDurationMs * 2;

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

  // Start tick interval (update every 100ms for responsive UI)
  clearTickInterval();
  tickIntervalId = setInterval(tick, 100);

  logger.info('[Sleep Timer] Started with config:', newConfig);
}

function stopSleepTimer(): void {
  clearTickInterval();
  cancelCurrentFade();

  // Stop and dispose pink noise
  if (pinkNoiseGenerator) {
    pinkNoiseGenerator.dispose();
    pinkNoiseGenerator = null;
  }

  // Restore music volume
  player.setVolume(savedMusicVolume);

  sleepTimerStore.set({
    ...initialState,
    config: get(sleepTimerStore).config, // Preserve saved config
  });

  logger.info('[Sleep Timer] Stopped');
}

async function cancel(): Promise<void> {
  const state = get(sleepTimerStore);

  clearTickInterval();
  cancelCurrentFade();

  // If we're in noise phase, stop the noise and resume music
  if (pinkNoiseGenerator) {
    pinkNoiseGenerator.dispose();
    pinkNoiseGenerator = null;
  }

  // Restore music volume
  player.setVolume(savedMusicVolume);

  // If we paused the music, try to resume
  if (state.phase !== 'music' && state.phase !== 'idle') {
    try {
      await player.resume();
    } catch {
      // Music might have ended, that's okay
    }
  }

  sleepTimerStore.set({
    ...initialState,
    config: state.config, // Preserve saved config
  });

  logger.info('[Sleep Timer] Cancelled');
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
export const phaseRemainingMs = derived(sleepTimerStore, $s => $s.phaseRemainingMs);
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
