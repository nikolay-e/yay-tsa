import { createLogger } from '@yaytsa/core';

const log = createLogger('VocalRemoval');

export interface VocalRemovalConfig {
  bassPreservationCutoff?: number;
  enabled?: boolean;
}

export class VocalRemovalProcessor {
  private audioContext: AudioContext;
  private sourceNode: MediaElementAudioSourceNode | null = null;
  private splitter: ChannelSplitterNode | null = null;
  private merger: ChannelMergerNode | null = null;
  private highPassFilter: BiquadFilterNode | null = null;
  private lowPassFilter: BiquadFilterNode | null = null;
  private invertGainRight: GainNode | null = null;
  private invertGainLeft: GainNode | null = null;
  private outputGain: GainNode | null = null;
  private isConnected: boolean = false;
  private _enabled: boolean = false;
  private bassPreservationCutoff: number;

  constructor(audioContext: AudioContext, config: VocalRemovalConfig = {}) {
    this.audioContext = audioContext;
    this.bassPreservationCutoff = config.bassPreservationCutoff ?? 120;
    this._enabled = config.enabled ?? false;
  }

  get enabled(): boolean {
    return this._enabled;
  }

  connectToAudioElement(audioElement: HTMLAudioElement): void {
    if (this.isConnected) {
      log.warn('Already connected to an audio element');
      return;
    }

    try {
      this.sourceNode = this.audioContext.createMediaElementSource(audioElement);
      this.createProcessingNodes();
      this.isConnected = true;

      if (this._enabled) {
        this.connectEffectChain();
      } else {
        this.connectBypass();
      }

      log.info('Vocal removal processor connected');
    } catch (error) {
      log.error('Failed to connect vocal removal processor', { error: String(error) });
      throw error;
    }
  }

  private createProcessingNodes(): void {
    this.splitter = this.audioContext.createChannelSplitter(2);
    this.merger = this.audioContext.createChannelMerger(2);

    this.highPassFilter = this.audioContext.createBiquadFilter();
    this.highPassFilter.type = 'highpass';
    this.highPassFilter.frequency.value = this.bassPreservationCutoff;
    this.highPassFilter.Q.value = 0.7;

    this.lowPassFilter = this.audioContext.createBiquadFilter();
    this.lowPassFilter.type = 'lowpass';
    this.lowPassFilter.frequency.value = this.bassPreservationCutoff;
    this.lowPassFilter.Q.value = 0.7;

    this.invertGainRight = this.audioContext.createGain();
    this.invertGainRight.gain.value = -1;

    this.invertGainLeft = this.audioContext.createGain();
    this.invertGainLeft.gain.value = -1;

    this.outputGain = this.audioContext.createGain();
    this.outputGain.gain.value = 1;
  }

  private disconnectAll(): void {
    if (this.sourceNode) {
      try {
        this.sourceNode.disconnect();
      } catch {
        // Already disconnected
      }
    }
    if (this.splitter) {
      try {
        this.splitter.disconnect();
      } catch {
        // Already disconnected
      }
    }
    if (this.merger) {
      try {
        this.merger.disconnect();
      } catch {
        // Already disconnected
      }
    }
    if (this.highPassFilter) {
      try {
        this.highPassFilter.disconnect();
      } catch {
        // Already disconnected
      }
    }
    if (this.lowPassFilter) {
      try {
        this.lowPassFilter.disconnect();
      } catch {
        // Already disconnected
      }
    }
    if (this.invertGainRight) {
      try {
        this.invertGainRight.disconnect();
      } catch {
        // Already disconnected
      }
    }
    if (this.invertGainLeft) {
      try {
        this.invertGainLeft.disconnect();
      } catch {
        // Already disconnected
      }
    }
    if (this.outputGain) {
      try {
        this.outputGain.disconnect();
      } catch {
        // Already disconnected
      }
    }
  }

  private connectBypass(): void {
    if (!this.sourceNode || !this.outputGain) return;

    this.disconnectAll();
    this.sourceNode.connect(this.outputGain);
    this.outputGain.connect(this.audioContext.destination);
  }

  private connectEffectChain(): void {
    if (
      !this.sourceNode ||
      !this.splitter ||
      !this.merger ||
      !this.highPassFilter ||
      !this.lowPassFilter ||
      !this.invertGainRight ||
      !this.invertGainLeft ||
      !this.outputGain
    ) {
      return;
    }

    this.disconnectAll();

    // Low frequencies bypass (preserve bass)
    this.sourceNode.connect(this.lowPassFilter);
    this.lowPassFilter.connect(this.outputGain);

    // High frequencies go through phase cancellation
    this.sourceNode.connect(this.highPassFilter);
    this.highPassFilter.connect(this.splitter);

    // Left output: L - R (left channel + inverted right channel)
    this.splitter.connect(this.merger, 0, 0); // Left direct to left output
    this.splitter.connect(this.invertGainRight, 1); // Right to inverter
    this.invertGainRight.connect(this.merger, 0, 0); // Inverted right to left output

    // Right output: R - L (right channel + inverted left channel)
    this.splitter.connect(this.merger, 1, 1); // Right direct to right output
    this.splitter.connect(this.invertGainLeft, 0); // Left to inverter
    this.invertGainLeft.connect(this.merger, 0, 1); // Inverted left to right output

    this.merger.connect(this.outputGain);
    this.outputGain.connect(this.audioContext.destination);
  }

  setEnabled(enabled: boolean): void {
    if (this._enabled === enabled) return;

    this._enabled = enabled;
    const targetEnabled = enabled;

    if (!this.isConnected) return;

    // Use exponential ramp for smooth transition (prevents clicks)
    if (this.outputGain) {
      const currentTime = this.audioContext.currentTime;
      this.outputGain.gain.setValueAtTime(this.outputGain.gain.value, currentTime);
      this.outputGain.gain.exponentialRampToValueAtTime(0.001, currentTime + 0.05);

      // After fade out, switch chains
      setTimeout(() => {
        // Check if state hasn't changed while waiting
        if (this._enabled !== targetEnabled) return;

        if (targetEnabled) {
          this.connectEffectChain();
        } else {
          this.connectBypass();
        }

        // Fade back in
        if (this.outputGain) {
          const time = this.audioContext.currentTime;
          this.outputGain.gain.setValueAtTime(0.001, time);
          this.outputGain.gain.exponentialRampToValueAtTime(1, time + 0.05);
        }
      }, 50);
    }

    log.info(`Vocal removal ${enabled ? 'enabled' : 'disabled'}`);
  }

  toggle(): boolean {
    this.setEnabled(!this._enabled);
    return this._enabled;
  }

  setBassPreservationCutoff(frequency: number): void {
    this.bassPreservationCutoff = Math.max(50, Math.min(300, frequency));

    if (this.highPassFilter) {
      this.highPassFilter.frequency.setValueAtTime(
        this.bassPreservationCutoff,
        this.audioContext.currentTime
      );
    }
    if (this.lowPassFilter) {
      this.lowPassFilter.frequency.setValueAtTime(
        this.bassPreservationCutoff,
        this.audioContext.currentTime
      );
    }
  }

  dispose(): void {
    this.disconnectAll();

    this.sourceNode = null;
    this.splitter = null;
    this.merger = null;
    this.highPassFilter = null;
    this.lowPassFilter = null;
    this.invertGainRight = null;
    this.invertGainLeft = null;
    this.outputGain = null;
    this.isConnected = false;
    this._enabled = false;

    log.info('Vocal removal processor disposed');
  }
}
