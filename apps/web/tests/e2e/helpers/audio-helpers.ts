import type { Page } from '@playwright/test';

export interface AudioState {
  paused: boolean;
  currentTime: number;
  duration: number;
  volume: number;
  readyState: number;
  muted: boolean;
}

export interface AudioContextState {
  state: string;
  sampleRate: number;
  currentTime: number;
}

export async function getAudioElementState(page: Page): Promise<AudioState> {
  return page.evaluate(() => {
    const audio = document.querySelector('audio');
    if (!audio) {
      throw new Error('Audio element not found');
    }
    return {
      paused: audio.paused,
      currentTime: audio.currentTime,
      duration: audio.duration,
      volume: audio.volume,
      readyState: audio.readyState,
      muted: audio.muted,
    };
  });
}

export async function getAudioContextState(page: Page): Promise<AudioContextState | null> {
  return page.evaluate(() => {
    const player = (window as any).__playerStore__;
    const audioEngine = player?.audioEngine;
    const audioContext = audioEngine?.getAudioContext?.();

    if (!audioContext) {
      return null;
    }

    return {
      state: audioContext.state,
      sampleRate: audioContext.sampleRate,
      currentTime: audioContext.currentTime,
    };
  });
}

export async function getRMSLevel(page: Page): Promise<number> {
  return page.evaluate(() => {
    const player = (window as any).__playerStore__;
    const audioEngine = player?.audioEngine;

    audioEngine?.getAudioContext?.();

    const analyser = audioEngine?.analyser;

    if (!analyser) {
      return 0;
    }

    const dataArray = new Float32Array(analyser.fftSize);
    analyser.getFloatTimeDomainData(dataArray);

    const rms = Math.sqrt(
      dataArray.reduce((sum: number, val: number) => sum + val * val, 0) / dataArray.length
    );

    return rms;
  });
}

export async function monitorRMSLevels(
  page: Page,
  durationMs: number,
  sampleIntervalMs: number = 100
): Promise<number[]> {
  const samples: number[] = [];
  const startTime = Date.now();

  while (Date.now() - startTime < durationMs) {
    const rms = await getRMSLevel(page);
    samples.push(rms);
    await page.waitForTimeout(sampleIntervalMs);
  }

  return samples;
}

export async function monitorVolumeFade(
  page: Page,
  durationMs: number,
  sampleIntervalMs: number = 100
): Promise<number[]> {
  const samples: number[] = [];
  const startTime = Date.now();

  while (Date.now() - startTime < durationMs) {
    const volume = await page.evaluate(() => {
      const audio = document.querySelector('audio');
      return audio?.volume ?? 0;
    });
    samples.push(volume);
    await page.waitForTimeout(sampleIntervalMs);
  }

  return samples;
}

export async function waitForAudioContextRunning(
  page: Page,
  timeout: number = 5000
): Promise<void> {
  const startTime = Date.now();

  while (Date.now() - startTime < timeout) {
    const state = await getAudioContextState(page);
    if (state?.state === 'running') {
      return;
    }
    await page.waitForTimeout(100);
  }

  throw new Error(`AudioContext did not reach 'running' state within ${timeout}ms`);
}

export async function waitForRMSAboveThreshold(
  page: Page,
  threshold: number = 0.01,
  timeout: number = 5000
): Promise<void> {
  const startTime = Date.now();

  while (Date.now() - startTime < timeout) {
    const rms = await getRMSLevel(page);
    if (rms > threshold) {
      return;
    }
    await page.waitForTimeout(100);
  }

  throw new Error(`RMS level did not exceed threshold ${threshold} within ${timeout}ms`);
}

export async function waitForRMSBelowThreshold(
  page: Page,
  threshold: number = 0.001,
  timeout: number = 5000
): Promise<void> {
  const startTime = Date.now();

  while (Date.now() - startTime < timeout) {
    const rms = await getRMSLevel(page);
    if (rms < threshold) {
      return;
    }
    await page.waitForTimeout(100);
  }

  throw new Error(`RMS level did not drop below threshold ${threshold} within ${timeout}ms`);
}

export function calculateAverageRMS(samples: number[]): number {
  if (samples.length === 0) return 0;
  return samples.reduce((sum, val) => sum + val, 0) / samples.length;
}

export function isVolumeDecreasing(samples: number[]): boolean {
  if (samples.length < 2) return false;

  for (let i = 1; i < samples.length; i++) {
    if (samples[i] > samples[i - 1] + 0.05) {
      return false;
    }
  }

  return samples[0] > samples[samples.length - 1];
}

export function isVolumeIncreasing(samples: number[]): boolean {
  if (samples.length < 2) return false;

  for (let i = 1; i < samples.length; i++) {
    if (samples[i] < samples[i - 1] - 0.05) {
      return false;
    }
  }

  return samples[samples.length - 1] > samples[0];
}

export async function getVolumeFromLocalStorage(page: Page): Promise<number | null> {
  return page.evaluate(() => {
    const stored = localStorage.getItem('yaytsa_volume');
    return stored ? parseFloat(stored) : null;
  });
}

export async function setVolumeInLocalStorage(page: Page, volume: number): Promise<void> {
  await page.evaluate(vol => {
    localStorage.setItem('yaytsa_volume', vol.toString());
  }, volume);
}

export async function getVolumeFromPlayerStore(page: Page): Promise<number> {
  return page.evaluate(() => {
    const player = (window as any).__playerStore__;
    return player?.volume ?? 0;
  });
}
