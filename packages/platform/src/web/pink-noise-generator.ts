import { createLogger } from '@yaytsa/core';

const log = createLogger('PinkNoise');

export interface PinkNoiseConfig {
  initialVolume?: number;
  audioContext?: AudioContext;
}

export class PinkNoiseGenerator {
  private audioContext: AudioContext | null = null;
  private gainNode: GainNode | null = null;
  private noiseNode: AudioBufferSourceNode | null = null;
  private isPlaying: boolean = false;
  private currentFadeCancel: (() => void) | null = null;
  private ownsAudioContext: boolean = false;

  async start(config: PinkNoiseConfig = {}): Promise<void> {
    if (this.isPlaying) return;

    const { initialVolume = 0, audioContext } = config;

    // Use provided AudioContext or create a new one
    if (audioContext) {
      this.audioContext = audioContext;
      this.ownsAudioContext = false;
    } else if (!this.audioContext || this.audioContext.state === 'closed') {
      const AudioContextClass =
        window.AudioContext ||
        (window as typeof window & { webkitAudioContext?: typeof AudioContext }).webkitAudioContext;
      if (!AudioContextClass) {
        throw new Error('Web Audio API not supported');
      }
      this.audioContext = new AudioContextClass();
      this.ownsAudioContext = true;
    }

    // Resume if suspended (iOS requirement)
    if (this.audioContext.state === 'suspended') {
      await this.audioContext.resume();
    }

    // Create gain node for volume control
    this.gainNode = this.audioContext.createGain();
    this.gainNode.gain.setValueAtTime(initialVolume, this.audioContext.currentTime);
    this.gainNode.connect(this.audioContext.destination);

    // Generate pink noise buffer using Paul Kellet's refined method
    const bufferSize = 2 * this.audioContext.sampleRate; // 2 seconds of noise
    const buffer = this.audioContext.createBuffer(1, bufferSize, this.audioContext.sampleRate);
    const data = buffer.getChannelData(0);

    // Paul Kellet's pink noise algorithm coefficients
    let b0 = 0,
      b1 = 0,
      b2 = 0,
      b3 = 0,
      b4 = 0,
      b5 = 0,
      b6 = 0;

    for (let i = 0; i < bufferSize; i++) {
      const white = Math.random() * 2 - 1;

      b0 = 0.99886 * b0 + white * 0.0555179;
      b1 = 0.99332 * b1 + white * 0.0750759;
      b2 = 0.969 * b2 + white * 0.153852;
      b3 = 0.8665 * b3 + white * 0.3104856;
      b4 = 0.55 * b4 + white * 0.5329522;
      b5 = -0.7616 * b5 - white * 0.016898;

      // Combine filtered components with scaling factor from Csound
      data[i] = (b0 + b1 + b2 + b3 + b4 + b5 + b6 + white * 0.5362) * 0.11;
      b6 = white * 0.115926;
    }

    // Create and configure source node
    this.noiseNode = this.audioContext.createBufferSource();
    this.noiseNode.buffer = buffer;
    this.noiseNode.loop = true;
    this.noiseNode.connect(this.gainNode);
    this.noiseNode.start();

    this.isPlaying = true;
  }

  stop(): void {
    // Cancel any ongoing fade
    if (this.currentFadeCancel) {
      this.currentFadeCancel();
      this.currentFadeCancel = null;
    }

    if (this.noiseNode) {
      try {
        this.noiseNode.stop();
        this.noiseNode.disconnect();
      } catch (error) {
        // Node might already be stopped - log for debugging
        log.debug('Pink noise node stop/disconnect failed', { error: String(error) });
      }
      this.noiseNode = null;
    }

    if (this.gainNode) {
      this.gainNode.disconnect();
      this.gainNode = null;
    }

    this.isPlaying = false;
  }

  setVolume(level: number): void {
    if (this.gainNode && this.audioContext) {
      const clampedLevel = Math.max(0, Math.min(1, level));
      this.gainNode.gain.setValueAtTime(clampedLevel, this.audioContext.currentTime);
    }
  }

  getVolume(): number {
    return this.gainNode?.gain.value ?? 0;
  }

  fadeVolume(
    fromLevel: number,
    toLevel: number,
    durationMs: number
  ): { promise: Promise<void>; cancel: () => void } {
    // Cancel any existing fade
    if (this.currentFadeCancel) {
      this.currentFadeCancel();
    }

    const startTime = Date.now();
    const startVolume = Math.max(0, Math.min(1, fromLevel));
    const endVolume = Math.max(0, Math.min(1, toLevel));
    let cancelled = false;
    let intervalId: ReturnType<typeof setInterval> | null = null;

    const cancel = () => {
      cancelled = true;
      if (intervalId) {
        clearInterval(intervalId);
        intervalId = null;
      }
      if (this.currentFadeCancel === cancel) {
        this.currentFadeCancel = null;
      }
    };

    this.currentFadeCancel = cancel;

    const promise = new Promise<void>(resolve => {
      this.setVolume(startVolume);

      const FADE_INTERVAL_MS = 16;
      intervalId = setInterval(() => {
        if (cancelled) {
          resolve();
          return;
        }

        const elapsed = Date.now() - startTime;
        const progress = Math.min(elapsed / durationMs, 1);

        // Ease-in-out curve for smoother transitions
        const easedProgress =
          progress < 0.5 ? 2 * progress * progress : 1 - Math.pow(-2 * progress + 2, 2) / 2;

        const currentVolume = startVolume + (endVolume - startVolume) * easedProgress;
        this.setVolume(currentVolume);

        if (progress >= 1) {
          if (intervalId) {
            clearInterval(intervalId);
            intervalId = null;
          }
          this.currentFadeCancel = null;
          resolve();
        }
      }, FADE_INTERVAL_MS);
    });

    return { promise, cancel };
  }

  getIsPlaying(): boolean {
    return this.isPlaying;
  }

  dispose(): void {
    this.stop();

    // Only close AudioContext if we created it
    if (this.ownsAudioContext && this.audioContext && this.audioContext.state !== 'closed') {
      void this.audioContext.close();
    }
    this.audioContext = null;
    this.ownsAudioContext = false;
  }
}
