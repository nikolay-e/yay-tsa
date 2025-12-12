import { writable, derived, get } from 'svelte/store';
import { browser } from '$app/environment';
import { PinkNoiseGenerator } from '@yaytsa/platform';
import { player, volume as volumeStore } from './player.js';
import { logger } from '../utils/logger.js';

const SLEEP_TIMER_SETTINGS_KEY = 'yaytsa_sleep_timer_settings';
const PINK_NOISE_VOLUME_RATIO = 0.5;
const RMS_SAMPLE_INTERVAL_MS = 500;
const RMS_EMA_ALPHA = 0.3;
const MIN_RMS_THRESHOLD = 0.05;

class ExponentialMovingAverage {
  private value: number | null = null;

  constructor(private alpha: number) {}

  update(newValue: number): number {
    if (this.value === null) {
      this.value = newValue;
    } else {
      this.value = this.alpha * newValue + (1 - this.alpha) * this.value;
    }
    return this.value;
  }

  getValue(): number {
    return this.value ?? 0;
  }

  reset(): void {
    this.value = null;
  }
}

export type SleepTimerPhase = 'idle' | 'music' | 'crossfade-to-noise' | 'noise' | 'stopped';

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
let rmsIntervalId: ReturnType<typeof setInterval> | null = null;
let pinkNoiseGenerator: PinkNoiseGenerator | null = null;
let savedMusicVolume: number = 0.7;
let measuredTrackRMS: number = 0;
let rmsEMA: ExponentialMovingAverage | null = null;
let currentFadeCancel: (() => void) | null = null;

function clearTickInterval(): void {
  if (tickIntervalId) {
    clearInterval(tickIntervalId);
    tickIntervalId = null;
  }
}

function clearRMSInterval(): void {
  if (rmsIntervalId) {
    clearInterval(rmsIntervalId);
    rmsIntervalId = null;
  }
}

function startRMSTracking(): void {
  clearRMSInterval();
  rmsEMA = new ExponentialMovingAverage(RMS_EMA_ALPHA);
  measuredTrackRMS = 0;

  const playerState = get(player);
  const audioEngine = playerState.audioEngine;

  if (!audioEngine?.getRMS) {
    logger.warn('[Sleep Timer] RMS measurement not available');
    return;
  }

  const capturedEngine = audioEngine;

  rmsIntervalId = setInterval(() => {
    const rms = capturedEngine.getRMS?.() ?? 0;
    if (rms > 0 && rmsEMA) {
      const smoothedRMS = rmsEMA.update(rms);
      measuredTrackRMS = smoothedRMS;
    }
  }, RMS_SAMPLE_INTERVAL_MS);

  logger.info('[Sleep Timer] Started RMS tracking');
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
  const { crossfadeDurationMs } = state.config;

  clearRMSInterval();

  savedMusicVolume = get(volumeStore);

  const hasValidRMS = measuredTrackRMS > MIN_RMS_THRESHOLD;
  const adaptiveVolume = hasValidRMS ? measuredTrackRMS : savedMusicVolume;
  logger.info(
    `[Sleep Timer] Using ${hasValidRMS ? 'measured' : 'UI'} volume: ${adaptiveVolume.toFixed(3)} (RMS: ${measuredTrackRMS.toFixed(3)}, UI: ${savedMusicVolume.toFixed(3)})`
  );

  pinkNoiseGenerator ??= new PinkNoiseGenerator();

  const playerState = get(player);
  const audioEngine = playerState.audioEngine;
  const audioContext = audioEngine?.getAudioContext?.() ?? undefined;

  try {
    await pinkNoiseGenerator.start({ initialVolume: 0, audioContext });

    if (audioEngine?.fadeVolume) {
      const targetNoiseVolume = adaptiveVolume * PINK_NOISE_VOLUME_RATIO;
      const musicFade = audioEngine.fadeVolume(savedMusicVolume, 0, crossfadeDurationMs);
      const noiseFade = pinkNoiseGenerator.fadeVolume(0, targetNoiseVolume, crossfadeDurationMs);

      currentFadeCancel = () => {
        musicFade.cancel();
        noiseFade.cancel();
      };

      await Promise.all([musicFade.promise, noiseFade.promise]);
      currentFadeCancel = null;

      player.pause();

      await transitionToPhase('noise');
    } else {
      player.setVolume(0);
      player.pause();
      pinkNoiseGenerator.setVolume(adaptiveVolume * PINK_NOISE_VOLUME_RATIO);
      await transitionToPhase('noise');
    }
  } catch (error) {
    logger.error('[Sleep Timer] Crossfade error:', error);
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
    const hasValidRMS = measuredTrackRMS > MIN_RMS_THRESHOLD;
    const adaptiveVolume = hasValidRMS ? measuredTrackRMS : savedMusicVolume;
    const startVolume = adaptiveVolume * PINK_NOISE_VOLUME_RATIO;
    const fade = pinkNoiseGenerator.fadeVolume(startVolume, 0, noiseDurationMs);

    currentFadeCancel = fade.cancel;
    await fade.promise;
    currentFadeCancel = null;

    stopSleepTimer();
  } catch (error) {
    logger.error('[Sleep Timer] Gradual noise fade error:', error);
    stopSleepTimer();
  }
}

function tick(): void {
  const state = get(sleepTimerStore);
  if (!state.isActive || !state.startedAt || !state.phaseStartedAt) return;

  const now = Date.now();
  const { musicDurationMs, noiseDurationMs, crossfadeDurationMs } = state.config;

  // Calculate total and phase remaining time
  // Total = music + (crossfade + noise only if noise is enabled)
  const totalDuration =
    noiseDurationMs > 0 ? musicDurationMs + crossfadeDurationMs + noiseDurationMs : musicDurationMs;
  const totalElapsed = now - state.startedAt;
  const remainingMs = Math.max(0, totalDuration - totalElapsed);

  const phaseElapsed = now - state.phaseStartedAt;
  let phaseDuration = 0;
  let nextPhase: SleepTimerPhase | null = null;

  switch (state.phase) {
    case 'music':
      phaseDuration = musicDurationMs;
      nextPhase = noiseDurationMs > 0 ? 'crossfade-to-noise' : 'stopped';
      break;
    case 'crossfade-to-noise':
      phaseDuration = crossfadeDurationMs;
      nextPhase = 'noise';
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

  startRMSTracking();

  logger.info('[Sleep Timer] Started with config:', newConfig);
}

function stopSleepTimer(): void {
  clearTickInterval();
  clearRMSInterval();
  cancelCurrentFade();

  if (pinkNoiseGenerator) {
    pinkNoiseGenerator.dispose();
    pinkNoiseGenerator = null;
  }

  player.setVolume(savedMusicVolume);

  measuredTrackRMS = 0;
  rmsEMA = null;

  sleepTimerStore.set({
    ...initialState,
    config: get(sleepTimerStore).config,
  });

  logger.info('[Sleep Timer] Stopped');
}

async function cancel(): Promise<void> {
  const state = get(sleepTimerStore);

  clearTickInterval();
  clearRMSInterval();
  cancelCurrentFade();

  if (pinkNoiseGenerator) {
    pinkNoiseGenerator.dispose();
    pinkNoiseGenerator = null;
  }

  player.setVolume(savedMusicVolume);

  measuredTrackRMS = 0;
  rmsEMA = null;

  if (state.phase !== 'music' && state.phase !== 'idle') {
    try {
      await player.resume();
    } catch {
      // Music might have ended, that's okay
    }
  }

  sleepTimerStore.set({
    ...initialState,
    config: state.config,
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

if (browser) {
  sleepTimerStore.subscribe(state => {
    window.__sleepTimerStore__ = {
      isActive: state.isActive,
      phase: state.phase,
      remainingMs: state.remainingMs,
    };
  });
}
